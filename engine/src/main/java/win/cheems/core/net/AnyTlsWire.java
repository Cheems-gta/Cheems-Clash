package win.cheems.core.net;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** AnyTLS v2 wire primitives; NOT a complete client or a working Dialer. */
public final class AnyTlsWire {
  private AnyTlsWire() {}
  public static final int WASTE=0, SYN=1, PSH=2, FIN=3, SETTINGS=4,
      ALERT=5, UPDATE_PADDING=6, SYNACK=7, HEART_REQUEST=8,
      HEART_RESPONSE=9, SERVER_SETTINGS=10;
  public static final int MAX_DATA=65535;
  public static final class Frame {
    public final int command;
    public final long streamId;
    public final byte[] data;
    public Frame(int command,long streamId,byte[] data) {
      if(command<0||command>255||streamId<0||streamId>0xffffffffL||
          data==null||data.length>MAX_DATA) throw new IllegalArgumentException("Invalid frame");
      this.command=command;this.streamId=streamId;this.data=data.clone();
    }
  }
  public static void writeFrame(OutputStream output,Frame frame)throws IOException {
    DataOutputStream out=new DataOutputStream(output);
    out.writeByte(frame.command);
    out.writeInt((int)frame.streamId);
    out.writeShort(frame.data.length);
    out.write(frame.data);
  }
  public static Frame readFrame(InputStream input)throws IOException {
    DataInputStream in=new DataInputStream(input);
    int cmd=in.readUnsignedByte();
    long id=Integer.toUnsignedLong(in.readInt());
    int size=in.readUnsignedShort();
    byte[] data=new byte[size];
    in.readFully(data);
    return new Frame(cmd,id,data);
  }
  public static void writeAuthentication(OutputStream output,String password,byte[] padding)
      throws IOException {
    if(password==null||password.isEmpty()||padding==null||padding.length>MAX_DATA)
      throw new IllegalArgumentException("Invalid authentication");
    byte[] digest;
    try {digest=MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));}
    catch(NoSuchAlgorithmException e){throw new IOException(e);}
    DataOutputStream out=new DataOutputStream(output);
    out.write(digest);out.writeShort(padding.length);out.write(padding);
  }
  public static byte[] settings(String paddingMd5) {
    if(paddingMd5==null||!paddingMd5.matches("[0-9a-f]{32}"))
      throw new IllegalArgumentException("Invalid padding checksum");
    return ("v=2\nclient=Cheems-Core/0.3-dev\npadding-md5="+paddingMd5)
        .getBytes(StandardCharsets.UTF_8);
  }
}
