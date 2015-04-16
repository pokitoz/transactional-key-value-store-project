package ch.epfl.tkvs.transactionmanager.versioningunit;

import java.io.Serializable;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.apache.log4j.Logger;

import ch.epfl.tkvs.keyvaluestore.KeyValueStore;


public enum VersioningUnit {
    instance;

    private static Logger log = Logger.getLogger(VersioningUnit.class.getName());

    final static int PRIMARY_CACHE = 0;
    KeyValueStore kvStore;

    private Map<Integer, Cache> caches;
    private Cache primary;
    private Deque<Cache> tmpPrimary;
    private BackgroundCommitThread backgroundCommitThread = null;

    /**
     * MUST be called before first use. This initializes the module.
     */
    public void init() {
        if (backgroundCommitThread != null && backgroundCommitThread.isAlive()) {
        	backgroundCommitThread.stopNow();
        	try {
				backgroundCommitThread.join();
			} catch (InterruptedException e) {
				// TODO: think about it
				e.printStackTrace();
			}
        }
        
        caches = new ConcurrentHashMap<Integer, Cache>();
        primary = new Cache(PRIMARY_CACHE);
        tmpPrimary = new ConcurrentLinkedDeque<Cache>();
        
        backgroundCommitThread = new BackgroundCommitThread();
        backgroundCommitThread.start();
    }
    
    private class BackgroundCommitThread extends Thread {
    	private volatile boolean shouldRun = true;
    	
    	@Override
    	public void run() {
    		while (shouldRun) {

                if (!tmpPrimary.isEmpty()) {
                    Cache cacheToCommit = tmpPrimary.getLast();

                    for (Serializable key : cacheToCommit.getWrittenKeys()) {
                        primary.put(key, cacheToCommit.get(key));
                    }


                    tmpPrimary.removeLast();
                    caches.remove(cacheToCommit.getXid());
                }
            }
    	}
    	
    	public void stopNow() {
    		shouldRun = false;
    	}
    }

    public Serializable get(int xid, Serializable key) {
        log.info("Get xid: " + xid + "- key: " + key);
        Cache xactCache = caches.get(xid);

        if (xactCache != null) {
            Serializable value = xactCache.get(key);
            if (value != null) {
                return value;
            }
        }

        for (Cache c : tmpPrimary) {
            Serializable value = c.get(key);
            if (value != null) {
                return value;
            }
        }

        return primary.get(key);
    }

    public void put(int xid, Serializable key, Serializable value) {
        log.info("Put xid: " + xid + "- key: " + key + "- value: " + value);
        Cache xactCache = caches.get(xid);

        if (xactCache == null) {
            xactCache = new Cache(xid);

            caches.put(xid, xactCache);
        }

        xactCache.put(key, value);

    }

    public void commit(final int xid) {
        log.info("Commit xid: " + xid);
        
        tmpPrimary.addFirst((caches.get(xid)));
    }

    public void abort(int xid) {
        caches.remove(xid);
    }

}
