package ai.openclaw.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ChatMessageSerializationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void serializesToolMessageWithToolCallIdAndName() throws Exception {
    OpenAiCompatibleChatClient.ChatMessage m =
        OpenAiCompatibleChatClient.ChatMessage.tool("call_1", "echo", "{\"ok\":true}");
    Map<String, Object> map = OpenAiCompatibleChatClient.messageToRequestMap(m);
    String json = MAPPER.writeValueAsString(map);
    Assertions.assertTrue(json.contains("\"role\":\"tool\""));
    Assertions.assertTrue(json.contains("\"tool_call_id\":\"call_1\""));
    Assertions.assertTrue(json.contains("\"name\":\"echo\""));
    Assertions.assertTrue(json.contains("\"content\""));
  }

  @Test
  void serializesAssistantWithToolCallsOmitsEmptyContentWhenOnlyToolCalls() throws Exception {
    ArrayNode calls = MAPPER.createArrayNode();
    calls.addObject().put("id", "x").put("type", "function").set("function", MAPPER.createObjectNode().put("name", "echo").put("arguments", "{}"));
    OpenAiCompatibleChatClient.ChatMessage m =
        OpenAiCompatibleChatClient.ChatMessage.assistantWithToolCalls(null, calls);
    Map<String, Object> map = OpenAiCompatibleChatClient.messageToRequestMap(m);
    String json = MAPPER.writeValueAsString(map);
    Assertions.assertTrue(json.contains("\"tool_calls\""));
    Assertions.assertTrue(json.contains("\"role\":\"assistant\""));
  }
}
