package org.concordia.babycdf;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.util.*;

/** Only fixed categories and narrowly validated public metadata cross this boundary. */
final class GitHubFetchFailure extends IOException {
    enum Code { http_401,http_403,http_404,http_410,http_422,http_429,http_5xx,http_other,budget,deadline,size,parse,transport,interrupted,offline_no_evidence }
    enum Message { none,api_rate_limit_exceeded,pat_resource_not_accessible,not_found,must_have_admin_rights }
    final Code code;
    GitHubFetchFailure(Code code) { this(code,Message.none,Map.of()); }
    private GitHubFetchFailure(Code code,Message message,Map<String,String> metadata) {
        super(code.name()+(message==Message.none?"":":"+message.name())+(metadata.isEmpty()?"":" "+metadata));this.code=code;
    }
    static GitHubFetchFailure http(int status,HttpHeaders headers,byte[] body) {
        Code code=switch(status){case 401->Code.http_401;case 403->Code.http_403;case 404->Code.http_404;case 410->Code.http_410;case 422->Code.http_422;case 429->Code.http_429;default->status>=500&&status<=599?Code.http_5xx:Code.http_other;};
        Map<String,String> safe=new LinkedHashMap<>();
        if(status==403||status==429){
            for(String key:List.of("x-ratelimit-remaining","x-ratelimit-reset","retry-after"))headers.firstValue(key).filter(v->v.matches("[0-9]{1,18}")).ifPresent(v->safe.put(key,v));
            safe.put("x-github-sso-present",Boolean.toString(headers.firstValue("x-github-sso").isPresent()));
        }
        Message message=Message.none;
        try {String text=RuleVerdict.JSON.readTree(body).path("message").asText("");
            if(text.startsWith("API rate limit exceeded"))message=Message.api_rate_limit_exceeded;
            else if(text.startsWith("Resource not accessible by personal access token"))message=Message.pat_resource_not_accessible;
            else if(text.startsWith("Not Found"))message=Message.not_found;
            else if(text.startsWith("Must have admin rights"))message=Message.must_have_admin_rights;
        }catch(Exception ignored){}
        return new GitHubFetchFailure(code,message,safe);
    }
    GitHubFetchFailure bodyReadFailed(){return new GitHubFetchFailure(code,Message.none,Map.of("body_read_failed","true","status_metadata",getMessage().replace(code.name(),"")));}
    static String safeSummary(IOException failure){return failure instanceof GitHubFetchFailure?failure.getMessage():Code.transport.name();}
}
