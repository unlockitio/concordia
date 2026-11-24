// SPDX-License-Identifier: Apache-2.0
package org.concordia.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.concordia.api.Interfaces.*;

@Path("/v1/{domain}/{collection}")
@Produces(MediaType.APPLICATION_JSON)
public class VoteTopicResource {
    @Inject InterfaceQueries queries;
    @Inject ObjectMapper mapper;
    public record Error(String code) {}
    public record Cursor(int version, long snapshot, String context, String after) {}

    private Kind kind(String domain, String collection) { return Kind.resolve(domain,collection); }
    private long snapshot() { long s = queries.latest(); queries.validate(s); return s; }
    private Contract require(Kind kind, long s, String id) { return queries.find(kind,s,id).orElseThrow(NotFoundException::new); }

    @GET public Page list(@PathParam("domain") String domain, @PathParam("collection") String collection,
            @QueryParam("limit") @DefaultValue("20") String limit, @QueryParam("cursor") String cursor, @Context UriInfo uri) {
        Kind k = kind(domain,collection);
        return page(k, null, null, limit, cursor, uri);
    }
    @GET @Path("/{contractId}") public Contract detail(@PathParam("domain") String domain,
            @PathParam("collection") String collection, @PathParam("contractId") String id) {
        return require(kind(domain,collection),snapshot(),id);
    }
    @GET @Path("/{contractId}/{relation}") public Object related(@PathParam("domain") String domain,
            @PathParam("collection") String collection, @PathParam("contractId") String id,
            @PathParam("relation") String relation, @QueryParam("limit") @DefaultValue("20") String limit,
            @QueryParam("cursor") String cursor, @Context UriInfo uri) {
        Kind k = kind(domain,collection);
        if (k == Kind.VOTE_TOPIC && relation.equals("vote-records")) return page(Kind.VOTE_RECORD,k,id,limit,cursor,uri);
        boolean action = (k == Kind.VOTE_TOPIC || k == Kind.VOTE_RECORD) && relation.equals("action");
        boolean facet = k == Kind.BID && relation.equals("single-lot-quotes");
        if (!action && !facet) throw new NotFoundException();
        long s = snapshot();
        Contract parent = require(k,s,id);
        if (facet) return require(Kind.SINGLE_LOT_QUOTES,s,id);
        String target = k == Kind.VOTE_TOPIC ? ((VoteTopic)parent.view()).action() : ((VoteRecord)parent.view()).action();
        if (target == null) return new ActionLink("no_generic_reference",null,Long.toString(s));
        var found = queries.find(Kind.ACTION,s,target);
        return new ActionLink(found.isPresent() ? "available" : "target_unavailable",found.orElse(null),Long.toString(s));
    }
    private Page page(Kind k, Kind parentKind, String parentId, String requested, String token, UriInfo uri) {
        int limit;
        try { limit = Integer.parseInt(requested); } catch (NumberFormatException e) { throw new BadRequestException(); }
        if (limit < 1 || limit > 100 || (token == null && uri.getQueryParameters().containsKey("cursor"))) throw new BadRequestException();
        String context = k.name() + ":" + (parentKind == null ? "" : parentKind.name()) + ":" + (parentId == null ? "" : parentId);
        Cursor c = token == null ? null : decode(token,context);
        long s = c == null ? queries.latest() : c.snapshot();
        queries.validate(s);
        String resolution = null;
        if (parentKind != null) {
            var parent = (VoteTopic) require(parentKind,s,parentId).view();
            if (parent.resolution() == null || !parent.resolution().isObject()
                    || !parent.resolution().has("resolver") || !parent.resolution().has("resolverCid")
                    || !parent.resolution().has("mechanism") || !parent.resolution().has("id")) throw new PqsInterfaces.Unavailable(new IllegalStateException("Invalid resolution"));
            resolution = parent.resolution().toString();
        }
        var rows = queries.list(k,s,c == null ? null : c.after(),limit+1,resolution);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0,limit) : rows;
        return new Page(items,Long.toString(s),more ? encode(new Cursor(2,s,context,items.getLast().contractId())) : null);
    }
    String encode(Cursor cursor) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(cursor)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    Cursor decode(String token, String context) {
        try {
            if (token.length() > 16384) throw new IllegalArgumentException();
            Cursor c = mapper.readValue(Base64.getUrlDecoder().decode(token),Cursor.class);
            if (c.version()!=2 || c.snapshot()<0 || !context.equals(c.context()) || c.after()==null || c.after().isEmpty() || !encode(c).equals(token)) throw new IllegalArgumentException();
            return c;
        } catch (Exception e) { throw new BadRequestException(); }
    }
    @jakarta.ws.rs.ext.Provider
    public static class UnavailableMapper implements jakarta.ws.rs.ext.ExceptionMapper<PqsInterfaces.Unavailable> {
        public Response toResponse(PqsInterfaces.Unavailable e) { return Response.status(503).entity(new Error("pqs_unavailable")).build(); }
    }
}
