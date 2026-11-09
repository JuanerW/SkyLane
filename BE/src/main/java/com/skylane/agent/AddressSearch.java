package com.skylane.agent;
import com.fasterxml.jackson.databind.*;
import com.skylane.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
@Component
public class AddressSearch {
 private final ObjectMapper mapper;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 public AddressSearch(ObjectMapper mapper){this.mapper=mapper;}
 public JsonNode search(String query){
  try{
   String url="https://photon.komoot.io/api/?limit=5&bbox=-122.52,37.70,-122.35,37.84&q="+URLEncoder.encode(query,StandardCharsets.UTF_8);
   var res=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
   if(res.statusCode()!=200)throw new IllegalStateException();
   List<Object> places=new ArrayList<>();
   for(JsonNode feature:mapper.readTree(res.body()).path("features")){
    var props=feature.path("properties");var coords=feature.path("geometry").path("coordinates");
    if(coords.size()<2)continue;
    var parts=new ArrayList<String>();
    for(String field:List.of("name","housenumber","street","city")){
     String value=props.path(field).asText("");if(!value.isBlank())parts.add(value);
    }
    double lat=coords.get(1).asDouble(),lng=coords.get(0).asDouble();
    if(lat>=37.70 && lat<=37.84 && lng>=-122.52 && lng<=-122.35)
     places.add(Map.of("text",String.join(", ",parts),"lat",lat,"lng",lng));
    if(places.size()==5)break;
   }
   return mapper.valueToTree(Map.of("places",places));
  }catch(Exception e){
   if(e instanceof InterruptedException)Thread.currentThread().interrupt();
   throw new ApiException(HttpStatus.BAD_GATEWAY,"ADDRESS_SEARCH_UNAVAILABLE","Address lookup is temporarily unavailable");
  }
 }
}
