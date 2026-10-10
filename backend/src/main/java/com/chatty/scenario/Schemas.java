package com.chatty.scenario;
import static com.chatty.store.Json.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.chatty.api.Problem;
import java.util.*;
public final class Schemas {
  private Schemas(){}
  public static JsonNode object(Map<String,JsonNode> properties,String...required){return obj("type","object","properties",properties,"required",required,"additionalProperties",false);}
  public static JsonNode string(){return obj("type","string");}
  public static JsonNode fields(String...keys){Map<String,JsonNode> p=new LinkedHashMap<>();for(String k:keys)p.put(k,string());return object(p,keys);}
  public static void validate(JsonNode args,JsonNode schema){if(!args.isObject())throw invalid();for(JsonNode r:schema.path("required"))if(!args.hasNonNull(r.asText()))throw invalid();args.fieldNames().forEachRemaining(k->{JsonNode p=schema.path("properties").get(k);if(p==null)throw invalid();JsonNode v=args.get(k);switch(p.path("type").asText()){case "string"->{if(!v.isTextual()||v.asText().isBlank()||v.asText().length()>3000)throw invalid();}case "object"->validate(v,p);default->throw invalid();}});}
  private static Problem invalid(){return new Problem(422,"VALIDATION_FAILED","Tool arguments are incomplete or invalid.");}
}
