import com.android.apksig.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.*;
import java.util.*;
public final class SignPlatform {
 public static void main(String[] a)throws Exception {
  X509Certificate cert;
  try(InputStream in=new FileInputStream(a[0])) { cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in); }
  StringBuilder h=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()))h.append(String.format("%02x",b&255));
  if(!h.toString().equals("c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"))throw new SecurityException("OEM certificate mismatch");
  PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(Paths.get(a[1]))));
  Signature signature=Signature.getInstance("SHA256withRSA"); signature.initSign(key); signature.update("exact-key-match".getBytes("US-ASCII")); byte[] proof=signature.sign();
  signature.initVerify(cert);signature.update("exact-key-match".getBytes("US-ASCII"));if(!signature.verify(proof))throw new SecurityException("Private key mismatch");
  ApkSigner.SignerConfig cfg=new ApkSigner.SignerConfig.Builder("AOSP-platform",key,Collections.singletonList(cert)).build();
  new ApkSigner.Builder(Collections.singletonList(cfg)).setInputApk(new File(a[2])).setOutputApk(new File(a[3])).setMinSdkVersion(26).setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(false).build().sign();
  ApkVerifier.Result r=new ApkVerifier.Builder(new File(a[3])).setMinCheckedPlatformVersion(26).setMaxCheckedPlatformVersion(35).build().verify();
  System.out.println("verified="+r.isVerified()+" v2="+r.isVerifiedUsingV2Scheme()+" cert="+h+" errors="+r.getAllErrors());
  if(!r.isVerified()||!r.isVerifiedUsingV2Scheme()||r.getSignerCertificates().size()!=1||!Arrays.equals(r.getSignerCertificates().get(0).getEncoded(),cert.getEncoded()))throw new SecurityException("Final APK verification");
 }
}
