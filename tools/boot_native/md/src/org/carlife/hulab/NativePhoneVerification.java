package org.carlife.hulab;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/** CarLife 2.0 phone verification, reconstructed from the official Baidu MD.
 * This checksum is neither a certificate nor encrypted media support.
 * Identity comes from the exact MD_INFO sent in this logical session.
 */
final class NativePhoneVerification {
    private static final int MAX_CMD_BYTES=65535;
    private static final char[] HEX="0123456789abcdef".toCharArray();
    private final String brand,model,sdk;

    NativePhoneVerification(byte[] sentDeviceInfo)throws IOException {
        bounded(sentDeviceInfo);
        Map<Integer,Object> info=MdMain.Proto.parse(sentDeviceInfo);
        brand=string(info,4);model=string(info,14);sdk=string(info,20);
    }

    byte[] response(byte[] request)throws IOException {
        bounded(request);
        String challenge=string(MdMain.Proto.parse(request),1);
        // Baidu uses only split(";")[1]; later components are not part of the nonce.
        if(!challenge.startsWith("2.0;"))throw new IOException("AUTH_UNSUPPORTED_LEGACY: RSA challenge is not implemented");
        int end=challenge.indexOf(';',4);if(end<0)end=challenge.length();
        if(end==4)throw new IOException("AUTH_UNSUPPORTED_LEGACY: empty v2 nonce selects the legacy RSA branch");
        try {
            MessageDigest digest=MessageDigest.getInstance("MD5");
            digest.update(new byte[]{'C','A','R','L','I','F','E'});
            updateJniUtf8(digest,brand);updateJniUtf8(digest,model);updateJniUtf8(digest,sdk);
            updateJniUtf8(digest,challenge.substring(4,end));
            byte[] bytes=digest.digest();char[] hex=new char[bytes.length*2];
            for(int i=0;i<bytes.length;i++){int b=bytes[i]&255;hex[i*2]=HEX[b>>>4];hex[i*2+1]=HEX[b&15];}
            return MdMain.Proto.strings(1,new String(hex));
        }catch(NoSuchAlgorithmException unavailable){throw new IOException("CarLife v2 MD5 unavailable",unavailable);}
    }

    private static void bounded(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length>MAX_CMD_BYTES)throw new IOException("CarLife phone verification exceeds command payload bound");
    }
    private static String string(Map<Integer,Object> fields,int field)throws IOException {
        Object value=fields.get(field);
        if(!(value instanceof byte[]))throw new IOException("CarLife phone verification requires string field "+field);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap((byte[])value)).toString();}
        catch(CharacterCodingException malformed){throw new IOException("CarLife phone verification invalid UTF-8 field "+field,malformed);}
    }
    /** Official libencryption uses JNI GetStringUTFChars (modified UTF-8).
     * Preserve its NUL/supplementary-character semantics, not the platform charset.
     */
    private static void updateJniUtf8(MessageDigest digest,String text){
        for(int i=0;i<text.length();i++){
            int c=text.charAt(i);
            if(c>0&&c<128)digest.update((byte)c);
            else if(c<2048){digest.update((byte)(0xc0|(c>>>6)));digest.update((byte)(0x80|(c&63)));}
            else{digest.update((byte)(0xe0|(c>>>12)));digest.update((byte)(0x80|((c>>>6)&63)));digest.update((byte)(0x80|(c&63)));}
        }
    }
}
