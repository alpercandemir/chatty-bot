package com.chatty.store;
import static com.chatty.store.Json.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.function.Function;
@Component
public class WorkspaceStore {
  private final JdbcTemplate jdbc; private final TransactionTemplate tx;
  public final JsonNode seed, scenarios;
  public WorkspaceStore(JdbcTemplate jdbc,TransactionTemplate tx)throws Exception{
    this.jdbc=jdbc;this.tx=tx;
    seed=M.readTree(new ClassPathResource("demo/fixtures/seed.json").getInputStream());
    scenarios=M.readTree(new ClassPathResource("demo/scenarios.json").getInputStream());
  }
  public ObjectNode fresh(String pack){
    String base=pack.equals("commerce")?"demo-northstar":"demo-clearspace";
    ObjectNode s=obj("pack",pack,"generation",id("reset"),"resetAt",Instant.now().toString(),"fixtureVersion","1.0.0");
    for(String name:new String[]{"orders","shipments","knowledgeArticles","principals"}){
      ArrayNode a=s.putArray(name);for(JsonNode n:seed.path(name))if(n.path("workspaceId").asText().equals(base))a.add(n.deepCopy());
    }
    for(JsonNode n:s.path("orders")){
      ObjectNode o=(ObjectNode)n; JsonNode addr=find(seed.path("addresses"),str(o,"addressId"));
      ObjectNode a=(ObjectNode)addr.deepCopy(); a.remove(java.util.List.of("id","workspaceId","principalId"));o.set("deliveryAddress",a);
      o.put("placedAt",Instant.now().plusSeconds(o.path("placedAtOffsetMinutes").asLong()*60).toString());
    }
    for(JsonNode n:s.path("shipments")){
      ObjectNode sh=(ObjectNode)n;JsonNode offset=sh.get("estimatedDeliveryOffsetMinutes");
      if(offset==null||offset.isNull())sh.putNull("estimatedDeliveryAt");else sh.put("estimatedDeliveryAt",Instant.now().plusSeconds(offset.asLong()*60).toString());
      for(JsonNode ev:sh.path("events"))((ObjectNode)ev).put("occurredAt",Instant.now().plusSeconds(ev.path("occurredAtOffsetMinutes").asLong()*60).toString());
    }
    for(String k:new String[]{"conversations","runs","actions","operations","supportTickets","callbackRequests","faults"})s.putArray(k);
    return s;
  }
  public synchronized void ensure(String workspace,String pack){tx.executeWithoutResult(t->{if(jdbc.queryForObject("SELECT COUNT(*) FROM demo_workspace WHERE id=?",Integer.class,workspace)==0){ObjectNode initial=fresh(pack);initial.put("workspaceId",workspace);jdbc.update("INSERT INTO demo_workspace(id,document) VALUES (?,?)",workspace,initial.toString());syncKnowledge(workspace,initial);}});}
  public void syncKnowledge(String workspace,ObjectNode s){jdbc.update("DELETE FROM knowledge_article WHERE workspace_id=?",workspace);for(JsonNode a:s.path("knowledgeArticles"))jdbc.update("INSERT INTO knowledge_article(workspace_id,article_id,version,title,content) VALUES (?,?,?,?,?)",workspace,str(a,"id"),a.path("version").asInt(),str(a,"title"),str(a,"content"));}
  public <T>T read(String id,Function<ObjectNode,T> f){return f.apply(load(id,false));}
  public <T>T change(String id,Function<ObjectNode,T> f){return tx.execute(t->{ObjectNode s=load(id,true);T out=f.apply(s);jdbc.update("UPDATE demo_workspace SET document=?,revision=revision+1 WHERE id=?",s.toString(),id);return out;});}
  private ObjectNode load(String id,boolean lock){return (ObjectNode)parse(jdbc.queryForObject("SELECT document FROM demo_workspace WHERE id=?"+(lock?" FOR UPDATE":""),String.class,id));}
  public void purge(String workspace){tx.executeWithoutResult(t->{ObjectNode s=load(workspace,true);for(JsonNode a:s.path("actions"))if(java.util.Set.of("UNKNOWN","EXECUTING").contains(str(a,"status")))throw new com.chatty.api.Problem(409,"PURGE_BLOCKED","Reconcile pending operations first.");for(JsonNode r:s.path("runs"))if(java.util.Set.of("QUEUED","RUNNING").contains(str(r,"status")))throw new com.chatty.api.Problem(409,"PURGE_BLOCKED","Stop active runs first.");jdbc.update("DELETE FROM knowledge_article WHERE workspace_id=?",workspace);jdbc.update("DELETE FROM demo_session WHERE workspace_id=?",workspace);jdbc.update("DELETE FROM demo_workspace WHERE id=?",workspace);});}
  public void recover(){for(String w:jdbc.queryForList("SELECT id FROM demo_workspace",String.class))change(w,s->{for(JsonNode n:s.path("runs")){ObjectNode r=(ObjectNode)n;if(java.util.Set.of("QUEUED","RUNNING").contains(str(r,"status"))){r.put("status","FAILED");event(r,"recovery","Interrupted run recovered. Send a new message to retry.",obj("code","PROCESS_RESTART"));}}return null;});}
  public static void event(ObjectNode r,String type,String title,JsonNode detail){ArrayNode events=(ArrayNode)r.withArray("events");events.add(obj("id",events.size()+1,"runId",str(r,"id"),"type",type,"title",title,"at",Instant.now().toString(),"detail",detail));}
}
