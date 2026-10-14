package org.carlife.hulab;
import java.io.IOException;
/** Process-wide encoder ownership, retained after an unconfirmed cleanup. */
final class SourceLease {
    private Object owner;
    private String label;
    private boolean poisoned;
    synchronized void acquire(Object token,String description)throws IOException {
        if(token==null)throw new IOException("null capture owner");
        if(owner!=null)throw new IOException("capture lease busy: "+label+" cleanup_unconfirmed="+poisoned);
        owner=token;label=description;poisoned=false;
    }
    synchronized boolean release(Object token,boolean cleanupConfirmed){
        if(owner!=token)return false;
        if(!cleanupConfirmed){poisoned=true;return false;}
        owner=null;label=null;poisoned=false;return true;
    }
    synchronized boolean isHeld(){return owner!=null;}
    synchronized String state(){return owner==null?"free":label+" cleanup_unconfirmed="+poisoned;}
}
