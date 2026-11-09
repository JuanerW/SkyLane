package com.skylane.agent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrderQueryPolicyTest {
 @Test void explicitListRequestsRequireFreshData(){
  for(String text:new String[]{"Show my recent orders","Please list my orders","Can you check my orders?","my recent orders","查看我的订单","请帮我查一下最近的订单","最近的订单"})
   assertThat(OrderQueryPolicy.requestsList(text)).as(text).isTrue();
 }
 @Test void otherMessagesDoNotTriggerPrefetch(){
  for(String text:new String[]{"Hello","Please quote a cake delivery","Cancel this order","propose an order","请帮我下单","取消这个订单"})
   assertThat(OrderQueryPolicy.requestsList(text)).as(text).isFalse();
 }
}
