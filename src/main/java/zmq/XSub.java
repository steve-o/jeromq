/*
    Copyright (c) 2009-2011 250bpm s.r.o.
    Copyright (c) 2007-2009 iMatix Corporation
    Copyright (c) 2011-2012 Spotify AB
    Copyright (c) 2007-2011 Other contributors as noted in the AUTHORS file

    This file is part of 0MQ.

    0MQ is free software; you can redistribute it and/or modify it under
    the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation; either version 3 of the License, or
    (at your option) any later version.

    0MQ is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package zmq;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

public class XSub extends SocketBase {
    
    public static class XSubSession extends SessionBase {

        public XSubSession(IOThread io_thread_, boolean connect_,
                SocketBase socket_, Options options_, Address addr_) {
            super(io_thread_, connect_, socket_, options_, addr_);
        }
        
    }
    
    //  Fair queueing object for inbound pipes.
    private final FQ fq;

    //  Object for distributing the subscriptions upstream.
    private final Dist dist;

    //  The repository of subscriptions.
    private final Trie subscriptions;

    //  If true, 'message' contains a matching message to return on the
    //  next recv call.
    private boolean has_message;
    private Msg message;

    //  If true, part of a multipart message was already received, but
    //  there are following parts still waiting.
    private boolean more;
    Trie.ITrieHandler send_subscription;

    
    public XSub (Ctx parent_, int tid_, int sid_) {
        super (parent_, tid_, sid_);
        
        options.type = ZMQ.ZMQ_XSUB;
        has_message = false;
        more = false;
        
        options.linger = 0;
        fq = new FQ();
        dist = new Dist();
        subscriptions = new Trie();
        
        Trie.ITrieHandler send_subscription = new Trie.ITrieHandler() {
            
            @Override
            public void added(byte[] data_, Object arg_) {
                
                Pipe pipe = (Pipe) arg_;

                //  Create the subsctription message.
                Msg msg = new Msg(data_.length + 1);
                msg.put((byte)1);
                msg.put(data_,1);

                //  Send it to the pipe.
                boolean sent = pipe.write (msg);
                //  If we reached the SNDHWM, and thus cannot send the subscription, drop
                //  the subscription message instead. This matches the behaviour of
                //  zmq_setsockopt(ZMQ_SUBSCRIBE, ...), which also drops subscriptions
                //  when the SNDHWM is reached.
                if (!sent)
                    msg.close ();

            }
        };
    }
    
    @Override
    protected void xattach_pipe (Pipe pipe_, boolean icanhasall_)
    {
        assert (pipe_ != null);
        fq.attach (pipe_);
        dist.attach (pipe_);

        //  Send all the cached subscriptions to the new upstream peer.
        subscriptions.apply (send_subscription, pipe_);
        pipe_.flush ();
    }
    
    @Override
    protected void xwrite_activated (Pipe pipe_)
    {
        dist.activated (pipe_);
    }
    
    @Override
    protected void xterminated (Pipe pipe_)
    {
        fq.terminated (pipe_);
        dist.terminated (pipe_);
    }
    
    @Override
    protected void xhiccuped (Pipe pipe_)
    {
        //  Send all the cached subscriptions to the hiccuped pipe.
        subscriptions.apply (send_subscription, pipe_);
        pipe_.flush ();
    }


    @Override
    protected boolean xsend (Msg msg_, int flags_)
    {   
        byte[] data = msg_.data(); 
        // Malformed subscriptions.
        if (data.length < 1 || (data[0] != 0 && data[0] != 1)) {
            //throw new IllegalArgumentException();
            return false;
        }
        
        // Process the subscription.
        if (data[0] == 1) {
            if (subscriptions.add (data , 1))
                return dist.send_to_all (msg_, flags_);
        }
        else {
            if (subscriptions.rm (data, 1))
                return dist.send_to_all (msg_, flags_);
        }

        return true;
    }

    @Override
    protected Msg xrecv (int flags_) {
        //  If there's already a message prepared by a previous call to zmq_poll,
        //  return it straight ahead.
        Msg msg_;
        if (has_message) {
            msg_ = new Msg(message);
            has_message = false;
            more = msg_.has_more();
            return msg_;
        }

        //  TODO: This can result in infinite loop in the case of continuous
        //  stream of non-matching messages which breaks the non-blocking recv
        //  semantics.
        while (true) {

            //  Get a message using fair queueing algorithm.
            msg_ = fq.recv ();

            //  If there's no message available, return immediately.
            //  The same when error occurs.
            if (msg_ == null)
                return null;

            //  Check whether the message matches at least one subscription.
            //  Non-initial parts of the message are passed 
            if (more || !options.filter || match (msg_)) {
                more = msg_.has_more();
                return msg_;
            }

            //  Message doesn't match. Pop any remaining parts of the message
            //  from the pipe.
            while (msg_.has_more()) {
                msg_ = fq.recv ();
                assert (msg_ != null);
            }
        }
    }

    private boolean match(Msg msg_) {
        return subscriptions.check ( msg_.data() );
    }
    
    @Override
    protected void xread_activated (Pipe pipe_) {
        fq.activated (pipe_);
    }
    
}
