package org.jeromq;

import org.jeromq.ZMQ.Socket;
import org.junit.Assert;
import org.junit.Test;

public class TestZThread
{

    @Test
    public void testDetached () 
    {
        ZThread.IDetachedRunnable detached = new ZThread.IDetachedRunnable() {
            
// JDK6            @Override
            public void run (Object[] args)
            {
                ZContext ctx = new ZContext ();
                assert (ctx != null);

                Socket push = ctx.createSocket (ZMQ.PUSH);
                assert (push != null);
                ctx.destroy ();
            }
        };
        
        ZThread.start (detached);
    }
    
    @Test
    public void testFork ()
    {
        ZContext ctx = new ZContext ();
        
        ZThread.IAttachedRunnable attached = new ZThread.IAttachedRunnable() {
            
// JDK6            @Override
            public void run (Object[] args, ZContext ctx, Socket pipe)
            {
                //  Create a socket to check it'll be automatically deleted
                ctx.createSocket (ZMQ.PUSH);
                pipe.recvStr ();
                pipe.send ("pong");
            }
        };
        
        Socket pipe = ZThread.fork (ctx, attached);
        assert (pipe != null);
        
        pipe.send ("ping");
        String pong = pipe.recvStr ();
        
        Assert.assertEquals (pong, "pong");
        
        //  Everything should be cleanly closed now
        ctx.destroy ();
    }
}
