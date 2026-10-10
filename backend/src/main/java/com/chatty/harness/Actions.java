package com.chatty.harness;
import static com.chatty.store.Json.*;
import static com.chatty.store.WorkspaceStore.event;
import com.chatty.api.*;
import com.chatty.scenario.*;
import com.chatty.store.WorkspaceStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
@Component
public class Actions {
  private final WorkspaceStore store;private final Map<String,ScenarioPack> packs=new HashMap<>();
  public Actions(WorkspaceStore s,List<ScenarioPack> ps){store=s;ps.forEach(p->packs.put(p.id(),p));}
  public ObjectNode get(Sessions.Session session,String id){return store.read(session.workspace(),s->own(s,session,id).deepCopy());}
  public static ObjectNode own(ObjectNode s,Sessions.Session session,String id){ObjectNode a=find(s.path("actions"),id);if(a==null||!str(a,"principalId").equals(session.principal()))throw Problem.missing();ObjectNode c=find(s.path("conversations"),str(a,"conversationId"));if(c==null||!str(c,"sessionToken").equals(session.token()))throw Problem.missing();return a;}
  public ObjectNode decide(Sessions.Session session,String id,boolean confirm){return store.change(session.workspace(),s->{ObjectNode a=own(s,session,id);ObjectNode c=find(s.path("conversations"),str(a,"conversationId")),r=find(s.path("runs"),str(a,"runId"));String status=str(a,"status");
    if(Set.of("SUCCEEDED","FAILED","REJECTED","UNKNOWN").contains(status))return a.deepCopy();
    if(!status.equals("PROPOSED"))throw new Problem(409,"ACTION_NOT_CONFIRMABLE","This proposal is no longer confirmable.");
    if(Instant.parse(str(a,"expiresAt")).isBefore(Instant.now())){a.put("status","EXPIRED");r.put("status","STOPPED");publish(c,r,a);return a.deepCopy();}
    if(!confirm){a.put("status","DECLINED");r.put("status","COMPLETED");publish(c,r,a);return a.deepCopy();}
    if(!str(a,"policyVersion").equals("1.0.0"))throw new Problem(409,"ACTION_NOT_CONFIRMABLE","Policy changed. Request a new preview.");
    a.put("status","CONFIRMED");event(r,"confirmation","Customer confirmed the saved proposal",obj("actionId",id));
    ToolContext x=new ToolContext(s,c,r,session.principal());String resource=str(a.path("args"),"orderId");
    if(x.fault("WRITE_FAILURE_BEFORE_COMMIT",resource)){a.put("status","FAILED");a.put("code","SIMULATED_WRITE_FAILED");a.put("error","The simulated system failed before committing. No change was made.");}
    else{try{
      // Domain validation and mutation happen under the same database row lock.
      JsonNode result=packs.get(str(s,"pack")).execute(a,x);a.put("status","EXECUTING");event(r,"execute","Recorded action dispatched",obj("operationId",str(a,"operationId")));a.set("result",result);
      s.withArray("operations").add(obj("id",str(a,"operationId"),"actionId",id,"status","SUCCEEDED","result",result));
      if(x.fault("WRITE_TIMEOUT_AFTER_COMMIT",resource)){a.put("status","UNKNOWN");event(r,"verify","Response lost after dispatch; outcome pending verification",obj("status","UNKNOWN","operationId",str(a,"operationId")));}else a.put("status","SUCCEEDED");
    }catch(Problem p){a.put("status","REJECTED");a.put("code",p.code);a.put("error",p.getMessage());}}
    r.put("status","COMPLETED");publish(c,r,a);return a.deepCopy();});}
  public ObjectNode reconcile(String workspace,String op){return store.change(workspace,s->{ObjectNode record=find(s.path("operations"),op);if(record==null)throw Problem.missing();ObjectNode a=find(s.path("actions"),str(record,"actionId"));if(str(a,"status").equals("UNKNOWN")){a.put("status",str(record,"status"));a.set("result",record.path("result"));publish(find(s.path("conversations"),str(a,"conversationId")),find(s.path("runs"),str(a,"runId")),a);}return a.deepCopy();});}
  public static String outcome(JsonNode a){String status=str(a,"status");if(status.equals("UNKNOWN"))return "The operation outcome is pending verification. No success is confirmed. Reference: "+str(a,"operationId")+". You can request support.";
    if(status.equals("PROPOSED"))return "Review the exact action and use the confirmation buttons. Nothing has been changed.";
    if(status.equals("DECLINED"))return "Kept unchanged. You declined this proposal.";
    if(Set.of("EXPIRED","SUPERSEDED").contains(status))return "This proposal is "+status.toLowerCase(Locale.ROOT)+". Request a new preview.";
    if(!status.equals("SUCCEEDED"))return a.path("error").asText("This operation has not completed.")+" ["+a.path("code").asText(status)+"]";
    return a.path("result").path("message").asText("Verified operation succeeded. Reference: "+str(a,"operationId"));
  }

  private static void publish(ObjectNode c,ObjectNode r,ObjectNode a){String message=outcome(a);c.withArray("messages").add(obj("id",id("msg"),"role","assistant","text",message,"actionId",str(a,"id"),"at",Instant.now().toString()));event(r,"outcome",message,obj("status",str(a,"status"),"operationId",str(a,"operationId"),"code",str(a,"code")));}
}
