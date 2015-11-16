package org.yamcs.web.rest;

import static io.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static io.netty.handler.codec.http.HttpHeaders.setContentLength;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.xtce.Algorithm;
import org.yamcs.xtce.MetaCommand;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.SequenceContainer;
import org.yamcs.xtce.XtceDb;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpHeaders.Values;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * These methods are looking for a better home. A ResponseBuilder ?
 */
public class RestUtils {
    
    private static final Logger log = LoggerFactory.getLogger(RestUtils.class);
    
    public static void sendResponse(RestResponse restResponse) throws RestException {
        if (restResponse == null) return; // Allowed, when the specific handler prefers to do this
        HttpResponse httpResponse;
        if (restResponse.getBody() == null) {
            httpResponse = new DefaultFullHttpResponse(HTTP_1_1, OK);
            setContentLength(httpResponse, 0);
        } else {
            httpResponse = new DefaultFullHttpResponse(HTTP_1_1, OK, restResponse.getBody());
            httpResponse.headers().set(HttpHeaders.Names.CONTENT_TYPE, restResponse.getContentType());
            setContentLength(httpResponse, restResponse.getBody().readableBytes());
        }

        RestRequest restRequest = restResponse.getRestRequest();
        ChannelFuture writeFuture = restRequest.getChannelHandlerContext().writeAndFlush(httpResponse);

        // Decide whether to close the connection or not.
        if (!isKeepAlive(restRequest.getHttpRequest())) {
            // Close the connection when the whole content is written out.
            writeFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }
    
    /**
     * Sends base HTTP response indicating that we'll use chunked transfer encoding
     */
    public static ChannelFuture startChunkedTransfer(RestRequest req, String contentType) {
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        response.headers().set(Names.TRANSFER_ENCODING, Values.CHUNKED);
        response.headers().set(Names.CONTENT_TYPE, contentType);
        
        ChannelHandlerContext ctx = req.getChannelHandlerContext();
        ChannelFuture writeFuture = ctx.writeAndFlush(response);
        writeFuture.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        return writeFuture;
    }
    
    public static ChannelFuture writeChunk(RestRequest req, ByteBuf buf) throws IOException {
        ChannelHandlerContext ctx = req.getChannelHandlerContext();
        Channel ch = ctx.channel();
        ChannelFuture writeFuture = ctx.writeAndFlush(new DefaultHttpContent(buf));
        try {
            while (!ch.isWritable() && ch.isOpen()) {
                writeFuture.await(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for channel to become writable", e);
            // TODO return? throw up?
        }
        return writeFuture;
    }
    
    /**
     * Send empty chunk downstream to signal end of response
     */
    public static void stopChunkedTransfer(RestRequest req) {
        ChannelHandlerContext ctx = req.getChannelHandlerContext();
        ChannelFuture chunkWriteFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        chunkWriteFuture.addListener(ChannelFutureListener.CLOSE);
    }
    
    /**
     * Returns true if the request specifies descending by use of the query string paramter 'order=desc'
     */
    public static boolean asksDescending(RestRequest req, boolean descendByDefault) throws RestException {
        if (req.hasQueryParameter("order")) {
            switch (req.getQueryParameter("order").toLowerCase()) {
            case "asc":
            case "ascending":
                return false;
            case "desc":
            case "descending":
                return true;
            default:
                throw new BadRequestException("Unsupported value for order parameter. Expected 'asc' or 'desc'");
            }            
        } else {
            return descendByDefault;
        }
    }
    
    public static IntervalResult scanForInterval(RestRequest req) throws RestException {
        return new IntervalResult(req);
    }
    
    /**
     * Searches for a valid parameter name in the URI of the request. It is
     * assumed that the MDB for the instance was already added to the request's
     * context.
     * 
     * @pathOffset the offset at which to start the search
     */
    public static MatchResult<Parameter> matchParameterName(RestRequest req, int pathOffset) {
        XtceDb mdb = req.getFromContext(MDBRequestHandler.CTX_MDB);
        
        MatchResult<String> nsMatch = matchXtceDbNamespace(req, pathOffset);
        NamedObjectId id = null;
        if (nsMatch.matches()) {
            String namespace = nsMatch.getMatch();
            if (req.hasPathSegment(nsMatch.getPathOffset())) {
                String name = req.getPathSegment(nsMatch.getPathOffset());
                id = NamedObjectId.newBuilder().setNamespace(namespace).setName(name).build();
                Parameter p = findParameter(mdb, id);
                if (p != null) {
                    return new MatchResult<>(p, nsMatch.getPathOffset() + 1, id);
                }
            }
        }

        return new MatchResult<>(null, -1);
    }
    
    public static Parameter findParameter(XtceDb mdb, NamedObjectId id) {
        Parameter p = mdb.getParameter(id);
        if(p==null) {
            p = mdb.getSystemParameterDb().getSystemParameter(id);
        }
        return p;
    }
    
    /**
     * Searches for a valid container name in the URI of the request. It is
     * assumed that the MDB for the instance was already added to the request's
     * context.
     * 
     * @pathOffset the offset at which to start the search
     */
    public static MatchResult<SequenceContainer> matchContainerName(RestRequest req, int pathOffset) {
        XtceDb mdb = req.getFromContext(MDBRequestHandler.CTX_MDB);
        
        MatchResult<String> nsMatch = matchXtceDbNamespace(req, pathOffset);
        NamedObjectId id = null;
        if (nsMatch.matches()) {
            String namespace = nsMatch.getMatch();
            if (req.hasPathSegment(nsMatch.getPathOffset())) {
                String name = req.getPathSegment(nsMatch.getPathOffset());
                id = NamedObjectId.newBuilder().setNamespace(namespace).setName(name).build();
                SequenceContainer c = mdb.getSequenceContainer(id);
                if (c != null) {
                    return new MatchResult<>(c, nsMatch.getPathOffset() + 1, id);
                }
            }
        }

        return new MatchResult<>(null, -1);
    }
    
    /**
     * Searches for a valid algorithm name in the URI of the request. It is
     * assumed that the MDB for the instance was already added to the request's
     * context.
     * 
     * @pathOffset the offset at which to start the search
     */
    public static MatchResult<Algorithm> matchAlgorithmName(RestRequest req, int pathOffset) {
        XtceDb mdb = req.getFromContext(MDBRequestHandler.CTX_MDB);
        
        MatchResult<String> nsMatch = matchXtceDbNamespace(req, pathOffset);
        NamedObjectId id = null;
        if (nsMatch.matches()) {
            String namespace = nsMatch.getMatch();
            if (req.hasPathSegment(nsMatch.getPathOffset())) {
                String name = req.getPathSegment(nsMatch.getPathOffset());
                id = NamedObjectId.newBuilder().setNamespace(namespace).setName(name).build();
                Algorithm a = mdb.getAlgorithm(id);
                if (a != null) {
                    return new MatchResult<>(a, nsMatch.getPathOffset() + 1, id);
                }
            }
        }

        return new MatchResult<>(null, -1);
    }
    
    /**
     * Searches for a valid command name in the URI of the request. It is
     * assumed that the MDB for the instance was already added to the request's
     * context.
     * 
     * @pathOffset the offset at which to start the search
     */
    public static MatchResult<MetaCommand> matchCommandName(RestRequest req, int pathOffset) {
        XtceDb mdb = req.getFromContext(MDBRequestHandler.CTX_MDB);
        
        MatchResult<String> nsMatch = matchXtceDbNamespace(req, pathOffset);
        NamedObjectId id = null;
        if (nsMatch.matches()) {
            String namespace = nsMatch.getMatch();
            if (req.hasPathSegment(nsMatch.getPathOffset())) {
                String name = req.getPathSegment(nsMatch.getPathOffset());
                id = NamedObjectId.newBuilder().setNamespace(namespace).setName(name).build();
                MetaCommand c = mdb.getMetaCommand(id);
                if (c != null) {
                    return new MatchResult<>(c, nsMatch.getPathOffset() + 1, id);
                }
            }
        }

        return new MatchResult<>(null, -1);
    }
    
    /**
     * Greedily matches a namespace
     */
    public static MatchResult<String> matchXtceDbNamespace(RestRequest req, int pathOffset) {
        XtceDb mdb = req.getFromContext(MDBRequestHandler.CTX_MDB);
        String matchedNamespace = null;
        
        String segment = req.getPathSegment(pathOffset);
        if (mdb.containsNamespace(segment)) {
            matchedNamespace = segment;
        } else if (mdb.containsNamespace("/" + segment)) {
            matchedNamespace = "/" + segment; 
        } else if (mdb.getSystemParameterDb().getYamcsSpaceSystem().getName().equals(segment)) {
            matchedNamespace = segment;
        }
        
        int beyond = pathOffset;
        if (matchedNamespace != null) {
            beyond++;
            if (matchedNamespace.startsWith("/")) {
                for (int i = pathOffset+1; i < req.getPathSegmentCount(); i++) {
                    String potential = matchedNamespace + "/" + req.getPathSegment(i);
                    if (mdb.containsNamespace(potential)) {
                        matchedNamespace = potential;
                        beyond++;
                    }
                }
            }
        }
        
        if (matchedNamespace != null) {
            return new MatchResult<>(matchedNamespace, beyond);
        } else {
            return new MatchResult<>(null, -1);
        }
    }
    
    public static class MatchResult <T> {
        private final NamedObjectId requestedId;
        private final T match;
        private final int pathOffset; // positioned after the match
        
        MatchResult(T match, int pathOffset, NamedObjectId requestedId) {
            this.match = match;
            this.pathOffset = pathOffset;
            this.requestedId = requestedId;
        }
        
        MatchResult(T match, int pathOffset) {
            this.match = match;
            this.pathOffset = pathOffset;
            requestedId = null;
        }
        
        public boolean matches() {
            return match != null;
        }
        
        public NamedObjectId getRequestedId() {
            return requestedId;
        }
        
        public T getMatch() {
            return match;
        }
        
        public int getPathOffset() {
            return pathOffset;
        }
    }
    
    public static class IntervalResult {
        private final long start;
        private final long stop;
        
        IntervalResult(RestRequest req) throws BadRequestException {
            start = req.getQueryParameterAsDate("start", TimeEncoding.INVALID_INSTANT);
            stop = req.getQueryParameterAsDate("stop", TimeEncoding.INVALID_INSTANT);
        }
        
        public boolean hasInterval() {
            return start != TimeEncoding.INVALID_INSTANT || stop != TimeEncoding.INVALID_INSTANT;
        }
        
        public boolean hasStart() {
            return start != TimeEncoding.INVALID_INSTANT;
        }
        
        public boolean hasStop() {
            return stop != TimeEncoding.INVALID_INSTANT;
        }
        
        public long getStart() {
            return start;
        }
        
        public long getStop() {
            return stop;
        }
        
        public String asSqlCondition(String col) {
            StringBuilder buf = new StringBuilder();
            if (start != TimeEncoding.INVALID_INSTANT) {
                buf.append(col).append(" >= ").append(start);
                if (stop != TimeEncoding.INVALID_INSTANT) {
                    buf.append(" and ").append(col).append(" < ").append(stop);
                }
            } else {
                buf.append(col).append(" < ").append(stop);
            }
            return buf.toString();
        }
    }
}