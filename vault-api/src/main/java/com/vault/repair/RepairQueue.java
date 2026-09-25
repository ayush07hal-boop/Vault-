package com.vault.repair;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * De-duplicating work queue of object ids awaiting repair. Repair runs behind this queue, drained by a
 * fixed number of workers, so a burst of failures can never starve client traffic of I/O.
 */
@Component
public class RepairQueue {

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /** @return false if the object was already waiting */
    public boolean enqueue(String objectId) {
        if (pending.add(objectId)) {
            queue.add(objectId);
            return true;
        }
        return false;
    }

    public int enqueueAll(Collection<String> objectIds) {
        int added = 0;
        for (String id : objectIds) {
            if (enqueue(id)) {
                added++;
            }
        }
        return added;
    }

    /** Blocks for the next id. It stops being "pending" as soon as it is taken, so it can be re-queued while running. */
    public String take() throws InterruptedException {
        String id = queue.take();
        pending.remove(id);
        return id;
    }

    public int size() {
        return queue.size();
    }
}
