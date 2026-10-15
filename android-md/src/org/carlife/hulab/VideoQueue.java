package org.carlife.hulab;

import java.util.ArrayDeque;

/** Complete video packets only. Never interrupts a packet already owned by the writer. */
final class VideoQueue<T> {
    static final int MAX_FRAMES=8, MAX_BYTES=256*1024, MAX_SINGLE_BYTES=AvcAccessUnits.MAX_AU+20;
    static final long MAX_AGE_MS=250, SYNC_INTERVAL_MS=1500;
    interface Adapter<T> {
        int bytes(T packet); long generation(T packet); long queuedAt(T packet);
        boolean recoveryPoint(T packet); boolean heartbeat(T packet);
    }
    static final class Snapshot {
        final int items; final long bytes,oldestAgeMs,dropped,staleDrops,pressureEvents,generation;
        final boolean awaitingIdr;
        Snapshot(int n,long b,long age,long drop,long stale,long pressure,long gen,boolean wait){items=n;bytes=b;oldestAgeMs=age;dropped=drop;staleDrops=stale;pressureEvents=pressure;generation=gen;awaitingIdr=wait;}
    }
    private final Adapter<T> adapter;
    private final ArrayDeque<T> items=new ArrayDeque<T>();
    private long bytes,generation=Long.MIN_VALUE,dropped,staleDrops,pressureEvents,lastSync=Long.MIN_VALUE;
    private boolean awaitingIdr=true,syncPending;
    VideoQueue(Adapter<T> adapter){if(adapter==null)throw new NullPointerException("adapter");this.adapter=adapter;}
    private void generation(long current){if(generation!=current){dropped+=items.size();items.clear();bytes=0;generation=current;awaitingIdr=true;syncPending=false;lastSync=Long.MIN_VALUE;}}
    private void loseChain(){dropped+=items.size();items.clear();bytes=0;awaitingIdr=true;syncPending=true;pressureEvents++;}
    private void expire(long now){
        while(!items.isEmpty()&&now-adapter.queuedAt(items.peek())>MAX_AGE_MS){
            if(adapter.heartbeat(items.peek())){bytes-=adapter.bytes(items.remove());dropped++;}
            else{loseChain();break;}
        }
    }
    synchronized boolean offer(T packet,long currentGeneration,long now){
        // A late producer must not reset a newer queue's generation.
        if(adapter.generation(packet)!=currentGeneration||currentGeneration<generation){staleDrops++;dropped++;return false;}
        generation(currentGeneration);expire(now);
        int size=adapter.bytes(packet);boolean heartbeat=adapter.heartbeat(packet),recovery=adapter.recoveryPoint(packet);
        if(size<0||size>MAX_SINGLE_BYTES){if(!heartbeat)loseChain();dropped++;return false;}
        if(now-adapter.queuedAt(packet)>MAX_AGE_MS){if(!heartbeat)loseChain();dropped++;return false;}
        if(!heartbeat&&awaitingIdr&&!recovery){dropped++;syncPending=true;return false;}
        if(items.size()>=MAX_FRAMES||(!items.isEmpty()&&bytes+size>MAX_BYTES)){
            if(heartbeat){dropped++;return false;}
            // Let an admitted large AU reach the writer. If a following P no longer
            // fits, keep this complete prefix but require a fresh IDR after the loss.
            boolean keepLargeAu=items.size()==1&&bytes>MAX_BYTES&&!recovery;
            if(keepLargeAu){awaitingIdr=true;syncPending=true;pressureEvents++;dropped++;return false;}
            loseChain();
            if(!recovery){dropped++;return false;}
        }
        if(!heartbeat&&recovery){awaitingIdr=false;syncPending=false;}
        // One complete AU may exceed the normal byte budget, alone only. A large P
        // reaches here only with an intact dependency chain and an empty queue.
        items.add(packet);bytes+=size;return true;
    }
    synchronized T poll(long currentGeneration,long now){
        if(currentGeneration<generation)return null;
        generation(currentGeneration);expire(now);
        T packet=items.poll();if(packet!=null)bytes-=adapter.bytes(packet);return packet;
    }
    synchronized Snapshot snapshot(long now){return new Snapshot(items.size(),bytes,items.isEmpty()?0:Math.max(0,now-adapter.queuedAt(items.peek())),dropped,staleDrops,pressureEvents,generation,awaitingIdr);}
    synchronized boolean consumeSyncRequest(long now){
        if(!syncPending||!awaitingIdr)return false;
        if(lastSync!=Long.MIN_VALUE&&now-lastSync<SYNC_INTERVAL_MS)return false;
        lastSync=now;return true;
    }
    synchronized void clear(){dropped+=items.size();items.clear();bytes=0;awaitingIdr=true;syncPending=false;lastSync=Long.MIN_VALUE;}
    synchronized boolean isEmpty(){return items.isEmpty();}
    synchronized int size(){return items.size();}
}
