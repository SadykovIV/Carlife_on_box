import org.carlife.selector.Protocol;
public final class ProtocolTest {
 public static void main(String[] ignored) {
  String n="00000000000000000000000000000000";
  Protocol.parse("V1\t"+n+"\tGET\t-");
  for(String s:new String[]{"android_auto","carplay","carlife"})Protocol.parse("V1\t"+n+"\tSELECT\t"+s);
  String reply=Protocol.reply(n,"carlife");
  byte[] raw=reply.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  if(raw[2]!=9 || raw[35]!=9 || raw[38]!=9 || raw[raw.length-1]!=10 || reply.indexOf('\\')>=0)throw new AssertionError("wire separators");
  if(!Protocol.reply(n,null).equals("V1\t"+n+"\tERROR\tREFUSED\n"))throw new AssertionError("error reply");
  for(String s:new String[]{"android_auto","carplay","carlife"}) {
   if(!s.equals(Protocol.helperReply("GET","-",s+"\n")))throw new AssertionError("GET stdout");
   if(!s.equals(Protocol.helperReply("SELECT",s,"selected="+s+"\neffective=UNCHANGED\nrequires_reboot=1\n")))throw new AssertionError("SELECT stdout");
   try {Protocol.helperReply("SELECT",s,"selected="+s+"\\n");throw new AssertionError("literal LF accepted");}catch(IllegalArgumentException expected){}
  }
  for(String bad:new String[]{"", "V1\\t"+n+"\\tGET\\t-", "V2\t"+n+"\tGET\t-", "V1\tx\tGET\t-", "V1\t"+n+"\tSELECT\t3", "V1\t"+n+"\tSELECT\tcarlife;reboot", "V1\t"+n+"\tSHELL\tid", "V1\t"+n+"\tSELECT\tcarlife\textra", "V1\t"+n+"\tGET\t-\nSELECT"}) {
   try {Protocol.parse(bad);throw new AssertionError("Accepted: "+bad);}catch(IllegalArgumentException expected){}
  }
  System.out.println("PASS actual TAB/LF bytes, 4 accepted/9 rejected envelopes, reply/error and 9 helper stdout cases; no Android runtime");
 }
}
