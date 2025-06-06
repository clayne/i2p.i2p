package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.Buffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.NoConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.router.CommSystemFacade.Status;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.FIFOBandwidthLimiter;
import net.i2p.stat.Rate;
import net.i2p.stat.RateAverages;
import net.i2p.stat.RateStat;
import net.i2p.util.TryCache;
import net.i2p.util.Addresses;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounter;
import net.i2p.util.SystemVersion;

/**
 *  The main NTCP NIO thread.
 */
class EventPumper implements Runnable {
    private final RouterContext _context;
    private final Log _log;
    private volatile boolean _alive;
    private Selector _selector;
    private final Set<NTCPConnection> _wantsWrite = new ConcurrentHashSet<NTCPConnection>(32);
    /**
     *  The following 3 are unbounded and lockless for performance in runDelayedEvents()
     */
    private final Queue<NTCPConnection> _wantsRead = new ConcurrentLinkedQueue<NTCPConnection>();
    private final Queue<ServerSocketChannel> _wantsRegister = new ConcurrentLinkedQueue<ServerSocketChannel>();
    private final Queue<NTCPConnection> _wantsConRegister = new ConcurrentLinkedQueue<NTCPConnection>();
    private final NTCPTransport _transport;
    private final ObjectCounter<String> _blockedIPs;
    private long _expireIdleWriteTime;
    private static final boolean _useDirect = false;
    private final boolean _nodelay;
    
    /**
     *  This probably doesn't need to be bigger than the largest typical
     *  message, which is a 5-slot VTBM (~2700 bytes).
     *  The occasional larger message can use multiple buffers.
     */
    private static final int BUF_SIZE = 8*1024;
    private static final int MAX_CACHE_SIZE = 64;

    private static class BufferFactory implements TryCache.ObjectFactory<ByteBuffer> {
        public ByteBuffer newInstance() {
            if (_useDirect) 
                return ByteBuffer.allocateDirect(BUF_SIZE);
            else
                return ByteBuffer.allocate(BUF_SIZE);
        }
    }
    

    /** 
     * every few seconds, iterate across all ntcp connections just to make sure
     * we have their interestOps set properly (and to expire any looong idle cons).
     * as the number of connections grows, we should try to make this happen
     * less frequently (or not at all), but while the connection count is small,
     * the time to iterate across them to check a few flags shouldn't be a problem.
     */
    private static final long FAILSAFE_ITERATION_FREQ = 2*1000l;
    private static final int FAILSAFE_LOOP_COUNT = 512;
    private static final long SELECTOR_LOOP_DELAY = 200;
    private static final long BLOCKED_IP_FREQ = 43*60*1000;

    /** tunnel test now disabled, but this should be long enough to allow an active tunnel to get started */
    private static final long MIN_EXPIRE_IDLE_TIME = 120*1000l;
    private static final long MAX_EXPIRE_IDLE_TIME = 11*60*1000l;
    private static final long MAY_DISCON_TIMEOUT = 10*1000;
    private static final long RI_STORE_INTERVAL = 29*60*1000;

    /**
     *  Do we use direct buffers for reading? Default false.
     *  NOT recommended as we don't keep good track of them so they will leak.
     *
     *  Unsupported, set _useDirect above.
     *
     *  @see java.nio.ByteBuffer
     */
    //private static final String PROP_DIRECT = "i2np.ntcp.useDirectBuffers";
    private static final String PROP_NODELAY = "i2np.ntcp.nodelay";

    private static final int MIN_MINB = 4;
    private static final int MAX_MINB = 12;
    private static final int MIN_BUFS;
    static {
        long maxMemory = SystemVersion.getMaxMemory();
        MIN_BUFS = (int) Math.max(MIN_MINB, Math.min(MAX_MINB, 1 + (maxMemory / (16*1024*1024))));
    }
    
    private static final TryCache<ByteBuffer> _bufferCache = new TryCache<>(new BufferFactory(), MIN_BUFS);

    private static final Set<Status> STATUS_OK =
        EnumSet.of(Status.OK, Status.IPV4_OK_IPV6_UNKNOWN, Status.IPV4_OK_IPV6_FIREWALLED);

    public EventPumper(RouterContext ctx, NTCPTransport transport) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
        _transport = transport;
        _expireIdleWriteTime = MAX_EXPIRE_IDLE_TIME;
        _blockedIPs = new ObjectCounter<String>();
        _context.statManager().createRateStat("ntcp.pumperKeySetSize", "", "ntcp", new long[] {10*60*1000} );
        //_context.statManager().createRateStat("ntcp.pumperKeysPerLoop", "", "ntcp", new long[] {10*60*1000} );
        _context.statManager().createRateStat("ntcp.pumperLoopsPerSecond", "", "ntcp", new long[] {10*60*1000} );
        _context.statManager().createRateStat("ntcp.zeroRead", "", "ntcp", new long[] {10*60*1000} );
        _context.statManager().createRateStat("ntcp.zeroReadDrop", "", "ntcp", new long[] {10*60*1000} );
        _context.statManager().createRateStat("ntcp.dropInboundNoMessage", "", "ntcp", new long[] {10*60*1000} );
        _context.statManager().createRequiredRateStat("ntcp.inboundConn", "Inbound NTCP Connection", "ntcp", new long[] { 60*1000L } );
        _nodelay = ctx.getBooleanPropertyDefaultTrue(PROP_NODELAY);
    }
    
    public synchronized void startPumping() {
        if (_log.shouldLog(Log.INFO))
            _log.info("Starting pumper");
        try {
            _selector = Selector.open();
            _alive = true;
            new I2PThread(this, "NTCP Pumper", true).start();
        } catch (IOException ioe) {
            _log.log(Log.CRIT, "Error opening the NTCP selector", ioe);
        } catch (java.lang.InternalError jlie) {
            // "unable to get address of epoll functions, pre-2.6 kernel?"
            _log.log(Log.CRIT, "Error opening the NTCP selector", jlie);
        }
    }
    
    public synchronized void stopPumping() {
        _alive = false;
        if (_selector != null && _selector.isOpen())
            _selector.wakeup();
    }
    
    /**
     *  Selector can take quite a while to close after calling stopPumping()
     */
    public boolean isAlive() {
        return _alive || (_selector != null && _selector.isOpen());
    }

    /**
     *  Register the acceptor.
     *  This is only called from NTCPTransport.bindAddress(), so it isn't clear
     *  why this needs a queue. 
     */
    public void register(ServerSocketChannel chan) {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("Registering server socket channel");
        _wantsRegister.offer(chan);
        _selector.wakeup();
    }

    /**
     *  Outbound
     */
    public void registerConnect(NTCPConnection con) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Registering " + con);
        _context.statManager().addRateData("ntcp.registerConnect", 1);
        _wantsConRegister.offer(con);
        _selector.wakeup();
    }
    
    /**
     *  The selector loop.
     *  On high-bandwidth routers, this is the thread with the highest CPU usage, so
     *  take care to minimize overhead and unnecessary debugging stuff.
     */
    public void run() {
        int loopCount = 0;
        int failsafeLoopCount = FAILSAFE_LOOP_COUNT;
        long lastFailsafeIteration = System.currentTimeMillis();
        long lastBlockedIPClear = lastFailsafeIteration;
        while (_alive && _selector.isOpen()) {
            try {
                loopCount++;

                try {
                    int count = _selector.select(SELECTOR_LOOP_DELAY);
                    if (count > 0) {
                        Set<SelectionKey> selected = _selector.selectedKeys();
                        //_context.statManager().addRateData("ntcp.pumperKeysPerLoop", selected.size());
                        processKeys(selected);
                        // does clear() do anything useful?
                        selected.clear();
                    }
                    runDelayedEvents();
                } catch (ClosedSelectorException cse) {
                    continue;
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Error selecting", ioe);
                } catch (CancelledKeyException cke) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Error selecting", cke);
		    continue;
		}
                
                long now = System.currentTimeMillis();
                if (lastFailsafeIteration + FAILSAFE_ITERATION_FREQ < now) {
                    // in the *cough* unthinkable possibility that there are bugs in
                    // the code, lets periodically pass over all NTCP connections and
                    // make sure that anything which should be able to write has been
                    // properly marked as such, etc
                    lastFailsafeIteration = now;
                    try {
                        Set<SelectionKey> all = _selector.keys();
                        int lastKeySetSize = all.size();
                        _context.statManager().addRateData("ntcp.pumperKeySetSize", lastKeySetSize);
                        _context.statManager().addRateData("ntcp.pumperLoopsPerSecond", loopCount / (FAILSAFE_ITERATION_FREQ / 1000));
                        // reset the failsafe loop counter,
                        // and recalculate the max loops before failsafe sleep, based on number of keys
                        loopCount = 0;
                        failsafeLoopCount = Math.max(FAILSAFE_LOOP_COUNT, 2 * lastKeySetSize);
                        
                        int failsafeWrites = 0;
                        int failsafeCloses = 0;
                        int failsafeInvalid = 0;

                        // Increase allowed idle time if we are well under allowed connections, otherwise decrease
                        boolean haveCap = _transport.haveCapacity(33);
                        if (haveCap)
                            _expireIdleWriteTime = Math.min(_expireIdleWriteTime + 1000, MAX_EXPIRE_IDLE_TIME);
                        else
                            _expireIdleWriteTime = Math.max(_expireIdleWriteTime - 3000, MIN_EXPIRE_IDLE_TIME);
                        for (SelectionKey key : all) {
                            try {
                                Object att = key.attachment();
                                if (!(att instanceof NTCPConnection))
                                    continue; // to the next con
                                NTCPConnection con = (NTCPConnection)att;
                                
                                /**
                                 * 100% CPU bug
                                 * http://forums.java.net/jive/thread.jspa?messageID=255525
                                 * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6595055
                                 * 
                                 * The problem is around a channel that was originally registered with Selector for i/o gets
                                 * closed on the server side (due to early client side exit).  But the server side can know
                                 * about such channel only when it does i/o (read/write) and thereby getting into an IO exception.
                                 * In this case, (bug 6595055)there are times (erroneous) when server side (selector) did not
                                 * know the channel is already closed (peer-reset), but continue to do the selection cycle on
                                 * a key set whose associated channel is alreay closed or invalid. Hence, selector's slect(..)
                                 * keep spinging with zero return without blocking for the timeout period.
                                 * 
                                 * One fix is to have a provision in the application, to check if any of the Selector's keyset
                                 * is having a closed channel/or invalid registration due to channel closure.
                                 */
                                if ((!key.isValid()) &&
                                    (!((SocketChannel)key.channel()).isConnectionPending()) &&
                                    con.getTimeSinceCreated(now) > 2 * NTCPTransport.ESTABLISH_TIMEOUT) {
                                    if (_log.shouldLog(Log.INFO))
                                        _log.info("Removing invalid key for " + con);
                                    // this will cancel the key, and it will then be removed from the keyset
                                    con.close();
                                    key.cancel();
                                    failsafeInvalid++;
                                    continue;
                                }

                                synchronized(con.getWriteLock()) {
                                    if ( (!con.isWriteBufEmpty()) &&
                                         ((key.interestOps() & SelectionKey.OP_WRITE) == 0) ) {
                                        // the data queued to be sent has already passed through
                                        // the bw limiter and really just wants to get shoved
                                        // out the door asap.
                                        if (_log.shouldLog(Log.INFO))
                                            _log.info("Failsafe write for " + con);
                                        setInterest(key, SelectionKey.OP_WRITE);
                                        failsafeWrites++;
                                    }
                                }
                                
                                final long expire;
                                if ((!haveCap || !con.isInbound()) &&
                                    con.getMayDisconnect() &&
                                    con.getMessagesReceived() <= 2 && con.getMessagesSent() <= 1) {
                                    expire = MAY_DISCON_TIMEOUT;
                                    if (_log.shouldInfo())
                                        _log.info("Possible early disconnect for " + con);
                                } else {
                                    expire = _expireIdleWriteTime;
                                }

                                if ( con.getTimeSinceSend(now) > expire &&
                                     con.getTimeSinceReceive(now) > expire) {
                                    // we haven't sent or received anything in a really long time, so lets just close 'er up
                                    // con will cancel the key
                                    con.sendTerminationAndClose();
                                    if (_log.shouldInfo())
                                        _log.info("Failsafe or expire close for " + con);
                                    failsafeCloses++;
                                } else {
                                    // periodically send our RI
                                    long estab = con.getEstablishedOn();
                                    if (estab > 0) {
                                        long uptime = now - estab;
                                        if (uptime >= RI_STORE_INTERVAL) {
                                            long mod = uptime % RI_STORE_INTERVAL;
                                            if (mod < FAILSAFE_ITERATION_FREQ) {
                                                con.sendOurRouterInfo(false);
                                            }
                                        }
                                    }
                                }
                            } catch (CancelledKeyException cke) {
                                // cancelled while updating the interest ops.  ah well
                            }
                        }
                        if (failsafeWrites > 0)
                            _context.statManager().addRateData("ntcp.failsafeWrites", failsafeWrites);
                        if (failsafeCloses > 0)
                            _context.statManager().addRateData("ntcp.failsafeCloses", failsafeCloses);
                        if (failsafeInvalid > 0)
                            _context.statManager().addRateData("ntcp.failsafeInvalid", failsafeInvalid);
                    } catch (ClosedSelectorException cse) {
                        continue;
                    }
                } else {
                    // another 100% CPU workaround 
                    // TODO remove or only if we appear to be looping with no interest ops
                    if ((loopCount % failsafeLoopCount) == failsafeLoopCount - 1) {
                        if (_log.shouldLog(Log.INFO))
                            _log.info("EventPumper throttle " + loopCount + " loops in " +
                                      (now - lastFailsafeIteration) + " ms");
                        _context.statManager().addRateData("ntcp.failsafeThrottle", 1);
                        try {
                            Thread.sleep(25);
                        } catch (InterruptedException ie) {}
                    }
                }
                if (lastBlockedIPClear + BLOCKED_IP_FREQ < now) {
                    _blockedIPs.clear();
                    lastBlockedIPClear = now;
                }
            } catch (RuntimeException re) {
                _log.error("Error in the event pumper", re);
            }
        }
        try {
            if (_selector.isOpen()) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Closing down the event pumper with selection keys remaining");
                Set<SelectionKey> keys = _selector.keys();
                for (SelectionKey key : keys) {
                    try {
                        Object att = key.attachment();
                        if (att instanceof ServerSocketChannel) {
                            ServerSocketChannel chan = (ServerSocketChannel)att;
                            chan.close();
                            key.cancel();
                        } else if (att instanceof NTCPConnection) {
                            NTCPConnection con = (NTCPConnection)att;
                            con.close();
                            key.cancel();
                        }
                    } catch (IOException ke) {
                        _log.error("Error closing key " + key + " on pumper shutdown", ke);
                    }
                }
                _selector.close();
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Closing down the event pumper with no selection keys remaining");
            }
        } catch (IOException e) {
            _log.error("Error closing keys on pumper shutdown", e);
        }
        _wantsConRegister.clear();
        _wantsRead.clear();
        _wantsRegister.clear();
        _wantsWrite.clear();
    }
    
    /**
     *  Process all keys from the last select.
     *  High-frequency path in thread.
     */
    private void processKeys(Set<SelectionKey> selected) {
        for (SelectionKey key : selected) {
            try {
                int ops = key.readyOps();
                boolean accept = (ops & SelectionKey.OP_ACCEPT) != 0;
                boolean connect = (ops & SelectionKey.OP_CONNECT) != 0;
                boolean read = (ops & SelectionKey.OP_READ) != 0;
                boolean write = (ops & SelectionKey.OP_WRITE) != 0;
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("ready ops for : " + key
                //               + " accept? " + accept + " connect? " + connect
                //               + " read? " + read 
                //               + "/" + ((key.interestOps()&SelectionKey.OP_READ)!= 0)
                //               + " write? " + write 
                //               + "/" + ((key.interestOps()&SelectionKey.OP_WRITE)!= 0)
                //               + " on " + key.attachment()
                //               );
                if (accept) {
                    _context.statManager().addRateData("ntcp.accept", 1);
                    processAccept(key);
                }
                if (connect) {
                    clearInterest(key, SelectionKey.OP_CONNECT);
                    processConnect(key);
                }
                if (read) {
                    processRead(key);
                }
                if (write) {
                    processWrite(key);
                }
                //if (!(accept || connect || read || write)) {
                //    if (_log.shouldLog(Log.INFO))
                //        _log.info("key wanted nothing? con: " + key.attachment());
                //}
            } catch (CancelledKeyException cke) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("key cancelled");
            }
        }
    }

    /**
     *  Called by the connection when it has data ready to write (after bw allocation).
     *  Only wakeup if new.
     */
    public void wantsWrite(NTCPConnection con) {
        if (_wantsWrite.add(con)) {
            _selector.wakeup();
        }
    }

    /**
     *  This is only called from NTCPConnection.complete()
     *  if there is more data, which is rare (never?)
     *  so we don't need to check for dups or make _wantsRead a Set.
     */
    public void wantsRead(NTCPConnection con) {
        _wantsRead.offer(con);
        _selector.wakeup();
    }

    /**
     *  High-frequency path in thread.
     */
    private ByteBuffer acquireBuf() {
        return _bufferCache.acquire();
    }
    
    /**
     *  Return a read buffer to the pool.
     *  These buffers must be from acquireBuf(), i.e. capacity() == BUF_SIZE.
     *  High-frequency path in thread.
     */
    public static void releaseBuf(ByteBuffer buf) {
        // double check
        if (buf.capacity() < BUF_SIZE) {
            I2PAppContext.getGlobalContext().logManager().getLog(EventPumper.class).error("Bad size " + buf.capacity(), new Exception());
            return;
        }
        buf.clear();
        _bufferCache.release(buf);
    }
    
    private void processAccept(SelectionKey key) {
        ServerSocketChannel servChan = (ServerSocketChannel)key.attachment();
        try {
            SocketChannel chan = servChan.accept();
            // don't throw an NPE if the connect is gone again
            if(chan == null)
                return;
            chan.configureBlocking(false);

            byte[] ip = chan.socket().getInetAddress().getAddress();
            String ba = Addresses.toString(ip);
            if (_context.blocklist().isBlocklisted(ip)) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Receive session request from blocklisted IP: " + ba);
                try { chan.close(); } catch (IOException ioe) { }
                return;
            }
            if (!_context.commSystem().isExemptIncoming(Addresses.toCanonicalString(ba))) {
                if (!_transport.allowConnection()) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Receive session request but at connection limit: " + ba);
                    try { chan.close(); } catch (IOException ioe) { }
                    return;
                }

                int count = _blockedIPs.count(ba);
                if (count > 0) {
                    count = _blockedIPs.increment(ba);
                    if (_log.shouldLog(Log.WARN))
                       _log.warn("Blocking accept of IP with count " + count + ": " + ba);
                    _context.statManager().addRateData("ntcp.dropInboundNoMessage", count);
                    try { chan.close(); } catch (IOException ioe) { }
                    return;
                }

                if (!shouldAllowInboundEstablishment()) {
                    try { chan.close(); } catch (IOException ioe) { }
                    return;
                }
            }

            _context.statManager().addRateData("ntcp.inboundConn", 1);

            if (shouldSetKeepAlive(chan))
                chan.socket().setKeepAlive(true);
            if (_nodelay)
                chan.socket().setTcpNoDelay(true);

            SelectionKey ckey = chan.register(_selector, SelectionKey.OP_READ);
            NTCPConnection con = new NTCPConnection(_context, _transport, chan, ckey);
            ckey.attach(con);
            _transport.establishing(con);
        } catch (IOException ioe) {
            _log.error("Error accepting", ioe);
        }
    }

    /**
     * Should we allow another inbound establishment?
     * Used to throttle outbound hole punches.
     * @since 0.9.2
     */
    private boolean shouldAllowInboundEstablishment() {
        RateStat rs = _context.statManager().getRate("ntcp.inboundConn");
        if (rs == null)
            return true;
        Rate r = rs.getRate(60*1000);
        if (r == null)
            return true;
        int last;
        long periodStart;
        RateAverages ra = RateAverages.getTemp();
        synchronized(r) {
            last = (int) r.getLastEventCount();
            periodStart = r.getLastCoalesceDate();
            r.computeAverages(ra, true);
        }
        // compare incoming conns per ms, min of 1 per second or 60/minute
        if (last < 15)
            last = 15;
        int total = (int) ra.getTotalEventCount();
        int current = total - last;
        if (current <= 0)
            return true;
        // getLastEventCount() is normalized to the rate, so we use the canonical period
        int lastPeriod = 60*1000;
        double avg = ra.getAverage();
        int currentTime = (int) (_context.clock().now() - periodStart);
        if (currentTime <= 5*1000)
            return true;
        // compare incoming conns per ms
        // both of these are scaled by actual period in coalesce
        float lastRate = last / (float) lastPeriod;
        float currentRate = (float) (current / (double) currentTime);
        float factor = _transport.haveCapacity(95) ? 1.05f : 0.95f;
        float minThresh = factor * lastRate;
        if (currentRate > minThresh) {
            // chance in 128
            // max out at about 25% over the last rate
            int probAccept = Math.max(1, ((int) (4 * 128 * currentRate / minThresh)) - 512);
            if (probAccept >= 128 || _context.random().nextInt(128) < probAccept) {
                if (_log.shouldWarn())
                    _log.warn("Probabalistic drop incoming (p=" + probAccept  +
                              "/128) last rate " + last + "/min current rate " +
                              (int) (currentRate * 60*1000));
                return false;
            }
        }
        return true; 
    }
    
    private void processConnect(SelectionKey key) {
        final NTCPConnection con = (NTCPConnection)key.attachment();
        final SocketChannel chan = con.getChannel();
        try {
            boolean connected = chan.finishConnect();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("processing connect for " + con + ": connected? " + connected);
            if (connected) {
                if (shouldSetKeepAlive(chan))
                    chan.socket().setKeepAlive(true);
                if (_nodelay)
                    chan.socket().setTcpNoDelay(true);
                // key was already set when the channel was created, why do it again here?
                con.setKey(key);
                con.outboundConnected();
                _context.statManager().addRateData("ntcp.connectSuccessful", 1);
            } else {
                con.closeOnTimeout("connect failed", null);
                _transport.markUnreachable(con.getRemotePeer().calculateHash());
                _context.statManager().addRateData("ntcp.connectFailedTimeout", 1);
            }
        } catch (IOException ioe) {   // this is the usual failure path for a timeout or connect refused
            if (_log.shouldLog(Log.INFO))
                _log.info("Failed outbound " + con, ioe);
            con.closeOnTimeout("connect failed", ioe);
            _transport.markUnreachable(con.getRemotePeer().calculateHash());
            _context.statManager().addRateData("ntcp.connectFailedTimeoutIOE", 1);
        } catch (NoConnectionPendingException ncpe) {
            // ignore
            if (_log.shouldLog(Log.WARN))
                _log.warn("error connecting on " + con, ncpe);
        }
    }

    /**
     *  @since 0.9.20
     */
    private boolean shouldSetKeepAlive(SocketChannel chan) {
        if (chan.socket().getInetAddress() instanceof Inet6Address)
            return false;
        Status status = _context.commSystem().getStatus();
        return !STATUS_OK.contains(status);
    }

    /**
     *  OP_READ will always be set before this is called.
     *  This method will disable the interest if no more reads remain because of inbound bandwidth throttling.
     *  High-frequency path in thread.
     */
    private void processRead(SelectionKey key) {
        final NTCPConnection con = (NTCPConnection)key.attachment();
        final SocketChannel chan = con.getChannel();
        ByteBuffer buf = null;
        try {
            while (true) {
                buf = acquireBuf();
                int read = 0;
                int readThisTime;
                int readCount = 0;
                while ((readThisTime = chan.read(buf)) > 0)  {
                    read += readThisTime;
                    readCount++;
                }
                if (readThisTime < 0 && read == 0)
                    read = readThisTime;
                if (_log.shouldDebug())
                    _log.debug("Read " + read + " bytes total in " + readCount + " times from " + con);
                if (read < 0) {
                    if (con.isInbound() && con.getMessagesReceived() <= 0) {
                        InetAddress addr = chan.socket().getInetAddress();
                        int count;
                        if (addr != null) {
                            byte[] ip = addr.getAddress();
                            String ba = Addresses.toString(ip);
                            count = _blockedIPs.increment(ba);
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("EOF on inbound before receiving any, blocking IP " + ba + " with count " + count + ": " + con);
                        } else {
                            count = 1;
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("EOF on inbound before receiving any: " + con);
                        }
                        _context.statManager().addRateData("ntcp.dropInboundNoMessage", count);
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("EOF on " + con);
                    }
                    con.close();
                    releaseBuf(buf);
                    break;
                }
                if (read == 0) {
                    // stay interested
                    //key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                    releaseBuf(buf);
                    // workaround for channel stuck returning 0 all the time, causing 100% CPU
                    int consec = con.gotZeroRead();
                    if (consec >= 5) {
                        _context.statManager().addRateData("ntcp.zeroReadDrop", 1);
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("Fail safe zero read close " + con);
                        con.close();
                    } else {
                        _context.statManager().addRateData("ntcp.zeroRead", consec);
                        if (_log.shouldLog(Log.INFO))
                            _log.info("nothing to read for " + con + ", but stay interested");
                    }
                    break;
                }
                // Process the data received
                // clear counter for workaround above
                con.clearZeroRead();
                // go around again if we filled the buffer (so we can read more)
                boolean keepReading = !buf.hasRemaining();
                // ZERO COPY. The buffer will be returned in Reader.processRead()
                // not ByteBuffer to avoid Java 8/9 issues with flip()
                ((Buffer)buf).flip();
                FIFOBandwidthLimiter.Request req = _context.bandwidthLimiter().requestInbound(read, "NTCP read"); //con, buf);
                if (req.getPendingRequested() > 0) {
                    // rare since we generally don't throttle inbound
                    clearInterest(key, SelectionKey.OP_READ);
                    _context.statManager().addRateData("ntcp.queuedRecv", read);
                    con.queuedRecv(buf, req);
                    break;
                } else {
                    // stay interested
                    //key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                    con.recv(buf);
                    _context.statManager().addRateData("ntcp.read", read);
                    if (readThisTime < 0) {
                        // EOF, we're done
                        con.close();
                        break;
                    }
                    if (!keepReading)
                        break;
                }
            }  // while true
        } catch (CancelledKeyException cke) {
            if (buf != null)
                releaseBuf(buf);
            if (_log.shouldLog(Log.WARN)) _log.warn("error reading on " + con, cke);
            con.close();
            _context.statManager().addRateData("ntcp.readError", 1);
        } catch (IOException ioe) {
            // common, esp. at outbound connect time
            if (buf != null)
                releaseBuf(buf);
            if (con.isInbound() && con.getMessagesReceived() <= 0) {
                byte[] ip = con.getRemoteIP();
                int count;
                if (ip != null) {
                    String ba = Addresses.toString(ip);
                    count = _blockedIPs.increment(ba);
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Blocking IP " + ba + " with count " + count + ": " + con, ioe);
                } else {
                    count = 1;
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("IOE on inbound before receiving any: " + con, ioe);
                }
                _context.statManager().addRateData("ntcp.dropInboundNoMessage", count);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("error reading on " + con, ioe);
            }
            if (con.isEstablished()) {
                _context.statManager().addRateData("ntcp.readError", 1);
            } else {
                // Usually "connection reset by peer", probably a conn limit rejection?
                // although it could be a read failure during the DH handshake
                // Same stat as in processConnect()
                _context.statManager().addRateData("ntcp.connectFailedTimeoutIOE", 1);
                RouterIdentity rem = con.getRemotePeer();
                if (rem != null && !con.isInbound())
                    _transport.markUnreachable(rem.calculateHash());
            }
            con.close();
        } catch (NotYetConnectedException nyce) {
            if (buf != null)
                releaseBuf(buf);
            // ???
            clearInterest(key, SelectionKey.OP_READ);
            if (_log.shouldLog(Log.WARN))
                _log.warn("error reading on " + con, nyce);
        }
    }
    
    /**
     *  OP_WRITE will always be set before this is called.
     *  This method will disable the interest if no more writes remain.
     *  High-frequency path in thread.
     */
    private void processWrite(SelectionKey key) {
        final NTCPConnection con = (NTCPConnection)key.attachment();
        processWrite(con, key);
    }

    /**
     *  Asynchronous write all buffers to the channel.
     *  This method will disable the interest if no more writes remain.
     *  If this returns false, caller MUST call wantsWrite(con)
     *
     *  @param key non-null
     *  @return true if all buffers were completely written, false if buffers remain
     *  @since 0.9.53
     */
    public boolean processWrite(final NTCPConnection con, final SelectionKey key) {
        boolean rv = false;
        final SocketChannel chan = con.getChannel();
        try {
            synchronized(con.getWriteLock()) {
                while (true) {
                    ByteBuffer buf = con.getNextWriteBuf();
                    if (buf != null) {
                        if (buf.remaining() <= 0) {
                            con.removeWriteBuf(buf);
                            continue;
                        }
                        int written = chan.write(buf);
                        //totalWritten += written;
                        if (written == 0) {
                            if ( (buf.remaining() > 0) || (!con.isWriteBufEmpty()) ) {
                                // stay interested
                                //key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                            } else {
                                rv = true;
                            }
                            break;
                        } else if (buf.remaining() > 0) {
                            // stay interested
                            //key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                            break;
                        } else {
                            con.removeWriteBuf(buf);
                            //if (buffer time is too much, add OP_WRITE to the interest ops and break?)
                            // LOOP
                        }
                    } else {
                        // Nothing more to write
                        if (key.isValid()) {
                            rv = true;
                        }
                        break;
                    }
                }
                if (rv)
                    clearInterest(key, SelectionKey.OP_WRITE);
                else
                    setInterest(key, SelectionKey.OP_WRITE);
            }
        // catch and close outside the write lock to avoid deadlocks in NTCPCon.locked_close()
        } catch (CancelledKeyException cke) {
            if (_log.shouldLog(Log.WARN)) _log.warn("error writing on " + con, cke);
            _context.statManager().addRateData("ntcp.writeError", 1);
            con.close();
            rv = true;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.WARN)) _log.warn("error writing on " + con, ioe);
            _context.statManager().addRateData("ntcp.writeError", 1);
            con.close();
            rv = true;
        }
        return rv;
    }
    
    /**
     *  Pull off the 4 _wants* queues and update the interest ops,
     *  which may, according to the javadocs, be a "naive" implementation and block.
     *  High-frequency path in thread.
     */
    private void runDelayedEvents() {
        NTCPConnection con;
        while ((con = _wantsRead.poll()) != null) {
            SelectionKey key = con.getKey();
            try {
                setInterest(key, SelectionKey.OP_READ);
            } catch (CancelledKeyException cke) {
                // ignore, we remove/etc elsewhere
                if (_log.shouldLog(Log.WARN))
                    _log.warn("RDE CKE 1", cke);
            } catch (IllegalArgumentException iae) {
                // JamVM (Gentoo: jamvm-1.5.4, gnu-classpath-0.98+gmp)
                // throws
		//java.lang.IllegalArgumentException: java.io.IOException: Bad file descriptor
		//   at gnu.java.nio.EpollSelectionKeyImpl.interestOps(EpollSelectionKeyImpl.java:102)
		//   at net.i2p.router.transport.ntcp.EventPumper.runDelayedEvents(EventPumper.java:580)
		//   at net.i2p.router.transport.ntcp.EventPumper.run(EventPumper.java:109)
		//   at java.lang.Thread.run(Thread.java:745)
		//   at net.i2p.util.I2PThread.run(I2PThread.java:85)
		//Caused by: java.io.IOException: Bad file descriptor
		//   at gnu.java.nio.EpollSelectorImpl.epoll_modify(Native Method)
		//   at gnu.java.nio.EpollSelectorImpl.epoll_modify(EpollSelectorImpl.java:313)
		//   at gnu.java.nio.EpollSelectionKeyImpl.interestOps(EpollSelectionKeyImpl.java:97)
		//   ...4 more
                if (_log.shouldLog(Log.WARN))
                    _log.warn("gnu?", iae);
            }
        }

        // check before instantiating iterator for speed
        if (!_wantsWrite.isEmpty()) {
            for (Iterator<NTCPConnection> iter = _wantsWrite.iterator(); iter.hasNext(); ) {
                con = iter.next();
                iter.remove();
                if (con.isClosed())
                    continue;
                SelectionKey key = con.getKey();
                if (key == null)
                    continue;
                try {
                    setInterest(key, SelectionKey.OP_WRITE);
                } catch (CancelledKeyException cke) {
                   if (_log.shouldLog(Log.WARN))
                       _log.warn("RDE CKE 2", cke);
                    // ignore
                } catch (IllegalArgumentException iae) {
                    // see above
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("gnu?", iae);
                }
            }
        }
        
        // only when address changes
        ServerSocketChannel chan;
        while ((chan = _wantsRegister.poll()) != null) {
            try {
                SelectionKey key = chan.register(_selector, SelectionKey.OP_ACCEPT);
                key.attach(chan);
            } catch (ClosedChannelException cce) {
                if (_log.shouldLog(Log.WARN)) _log.warn("Error registering", cce);
            }
        }
        
        while ((con = _wantsConRegister.poll()) != null) {
            final SocketChannel schan = con.getChannel();
            try {
                SelectionKey key = schan.register(_selector, SelectionKey.OP_CONNECT);
                key.attach(con);
                con.setKey(key);
                RouterAddress naddr = con.getRemoteAddress();
                try {
                    // no DNS lookups, do not use hostnames
                    int port = naddr.getPort();
                    byte[] ip = naddr.getIP();
                    if (port <= 0 || ip == null)
                        throw new IOException("Invalid NTCP address: " + naddr);
                    InetSocketAddress saddr = new InetSocketAddress(InetAddress.getByAddress(ip), port);
                    boolean connected = schan.connect(saddr);
                    if (connected) {
                        // Never happens, we use nonblocking
                        setInterest(key, SelectionKey.OP_READ);
                        processConnect(key);
                    }
                } catch (IOException ioe) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("error connecting to " + Addresses.toString(naddr.getIP(), naddr.getPort()), ioe);
                    _context.statManager().addRateData("ntcp.connectFailedIOE", 1);
                    _transport.markUnreachable(con.getRemotePeer().calculateHash());
                    con.close(true);
                } catch (UnresolvedAddressException uae) {                    
                    if (_log.shouldLog(Log.WARN)) _log.warn("unresolved address connecting", uae);
                    //_context.statManager().addRateData("ntcp.connectFailedUnresolved", 1);
                    _transport.markUnreachable(con.getRemotePeer().calculateHash());
                    con.close(true);
                } catch (CancelledKeyException cke) {
                    con.close(false);
                }
            } catch (ClosedChannelException cce) {
                if (_log.shouldLog(Log.WARN)) _log.warn("Error registering", cce);
            }
        }
        
        long now = System.currentTimeMillis();
        if (_lastExpired + 1000 <= now) {
            expireTimedOut();
            _lastExpired = now;
        }
    }

    /**
     *  Temp. block inbound from this IP
     *
     *  @since 0.9.58
     */
    public void blockIP(byte[] ip) {
        if (ip == null)
            return;
        String ba = Addresses.toString(ip);
        _blockedIPs.increment(ba);
    }

    private long _lastExpired;

    private void expireTimedOut() {
        _transport.expireTimedOut();
    }

    public long getIdleTimeout() { return _expireIdleWriteTime; }

    /**
     *  Warning - caller should catch unchecked CancelledKeyException
     *
     *  @throws CancelledKeyException which is unchecked
     *  @since 0.9.53
     */
    public static void setInterest(SelectionKey key, int op) throws CancelledKeyException {
        synchronized(key) {
            int old = key.interestOps();
            if ((old & op) == 0)
                key.interestOps(old | op);
        }
    }

    /**
     *  Warning - caller should catch unchecked CancelledKeyException
     *
     *  @throws CancelledKeyException which is unchecked
     *  @since 0.9.53
     */
    public static void clearInterest(SelectionKey key, int op) throws CancelledKeyException {
        synchronized(key) {
            int old = key.interestOps();
            if ((old & op) != 0)
                key.interestOps(old & ~op);
        }
    }
}
