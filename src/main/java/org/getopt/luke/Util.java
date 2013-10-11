package org.getopt.luke;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.document.DateTools.Resolution;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.FieldType.NumericType;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.CompositeReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfo.DocValuesType;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.SlowCompositeReaderWrapper;
import org.apache.lucene.search.similarities.TFIDFSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

public class Util {
  
  public static String xmlEscape(String in) {
    if (in == null) return "";
    StringBuilder sb = new StringBuilder(in.length());
    for (int i = 0; i < in.length(); i++) {
      char c = in.charAt(i);
      switch (c) {
      case '&':
        sb.append("&amp;");
        break;
      case '>':
        sb.append("&gt;");
        break;
      case '<':
        sb.append("&lt;");
        break;
      case '"':
        sb.append("&quot;");
        break;
      case '\'':
        sb.append("&#039;");
        break;
      default:
        sb.append(c);
      }
    }
    return sb.toString();
  }
  
  public static String bytesToHex(BytesRef bytes, boolean wrap) {
    return bytesToHex(bytes.bytes, bytes.offset, bytes.length, wrap);
  }

  public static String bytesToHex(byte bytes[], int offset, int length, boolean wrap) {
    StringBuffer sb = new StringBuffer();
    boolean newLine = false;
    for (int i = offset; i < offset + length; ++i) {
      if (i > offset && !newLine) {
        sb.append(" ");
      }
      sb.append(Integer.toHexString(0x0100 + (bytes[i] & 0x00FF))
                       .substring(1));
      if (i > 0 && (i + 1) % 16 == 0 && wrap) {
        sb.append("\n");
        newLine = true;
      } else {
        newLine = false;
      }
    }
    return sb.toString();
  }

  private static final byte[] EMPTY_BYTES = new byte[0];

  public static byte[] hexToBytes(String hex) {
    if (hex == null) {
      return EMPTY_BYTES;
    }
    hex = hex.replaceAll("\\s+", "");
    if (hex.length() == 0) {
      return EMPTY_BYTES;
    }
    ByteArrayOutputStream baos = new ByteArrayOutputStream(hex.length() / 2);
    byte b;
    for (int i = 0; i < hex.length(); i++) {
      int high = charToNibble(hex.charAt(i));
      int low = 0;
      if (i < hex.length() - 1) {
        i++;
        low = charToNibble(hex.charAt(i));
      }
      b = (byte)(high << 4 | low);
      baos.write(b);
    }
    return baos.toByteArray();
  }

  static final int charToNibble(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    } else if (c >= 'a' && c <= 'f') {
      return 0xa + (c - 'a');
    } else if (c >= 'A' && c <= 'F') {
      return 0xA + (c - 'A');
    } else {
      throw new RuntimeException("Not a hex character: '" + c + "'");
    }
  }

  public static String byteToHex(byte b) {
    return bytesToHex(new byte[]{b}, 0, 1, false);
  }

  public static String escape(String[] values, String sep) {
    if (values == null) return null;
    if (values.length == 0) return "";
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) sb.append(sep);
      sb.append(escape(values[i]));
    }
    return sb.toString();
  }

  public static String escape(String value) {
    if (value == null) return null;
    if (value.length() == 0) return value;
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch(c) {
        case '\t':
          sb.append("\\t");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\n':
          sb.append("\\n");
          break;
        default:
          if (((int)c >= 32 && (int)c <= 127) || Character.isLetterOrDigit(c)) {
            sb.append(c);
          } else {
            sb.append("&#" + (int)c + ";");
          }
      }
    }
    return sb.toString();
  }
  
  public static Collection<String> fieldNames(IndexReader r, boolean indexedOnly) throws IOException {
    AtomicReader reader;
    if (r instanceof CompositeReader) {
      reader = SlowCompositeReaderWrapper.wrap(r);
    } else {
      reader = (AtomicReader)r;
    }
    Set<String> res = new HashSet<String>();
    FieldInfos infos = reader.getFieldInfos();
    for (FieldInfo info : infos) {
      if (indexedOnly && info.isIndexed()) {
        res.add(info.name);
        continue;
      }
      res.add(info.name);
    }
    return res;
  }
  
  public static float decodeNormValue(long v, String fieldName, TFIDFSimilarity sim) throws Exception {
    try {
      return sim.decodeNormValue(v);
    } catch (Exception e) {
      throw new Exception("ERROR decoding norm for field "  + fieldName + ":" + e.toString());
    }
  }
  
  public static long encodeNormValue(float v, String fieldName, TFIDFSimilarity sim) throws Exception {
    try {
      return sim.encodeNormValue(v);
    } catch (Exception e) {
      throw new Exception("ERROR encoding norm for field "  + fieldName + ":" + e.toString());
    }    
  }


  // IdfpoPSVBNtxx#txxDtxx
  public static String fieldFlags(Field fld, FieldInfo info) {
    FieldType t = null;
    BytesRef binary = null;
    Number numeric = null;
    if (fld == null) {
      t = new FieldType();
      t.setIndexed(false);
      t.setStored(false);
      t.setStoreTermVectors(false);
      t.setOmitNorms(true);
      t.setStoreTermVectorOffsets(false);
      t.setStoreTermVectorPositions(false);
      t.setTokenized(false);
      t.setNumericType(null);
    } else {
      t = fld.fieldType();
      binary = fld.binaryValue();
      numeric = fld.numericValue();
    }
    StringBuffer flags = new StringBuffer();
    if (info.isIndexed()) flags.append("I");
    else flags.append("-");
    IndexOptions opts = info.getIndexOptions();
    if (info.isIndexed() && opts != null) {
      switch (opts) {
      case DOCS_ONLY:
        flags.append("d---");
        break;
      case DOCS_AND_FREQS:
        flags.append("df--");
        break;
      case DOCS_AND_FREQS_AND_POSITIONS:
        flags.append("dfp-");
        break;
      case DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS:
        flags.append("dfpo");
      }
    } else {
      flags.append("----");
    }
    if (info.hasPayloads()) flags.append("P");
    else flags.append("-");
    if (t.stored()) flags.append("S");
    else flags.append("-");
    if (t.storeTermVectors()) flags.append("V");
    else flags.append("-");
    if (binary != null) flags.append("B");
    else flags.append("-");
    if (info.hasNorms()) {
      flags.append("N");
      flags.append(dvToString(info.getNormType()));
    }
    else flags.append("----");
    if (numeric != null) {
      flags.append("#");
      NumericType nt = t.numericType();
      if (nt != null) {
        flags.append(nt.toString().charAt(0));
        int prec = t.numericPrecisionStep();
        String p = Integer.toHexString(prec);
        if (p.length() == 1) {
          p = "0" + p;
        }
        flags.append(p);
      } else {
        // try faking it
        if (numeric instanceof Integer) {
          flags.append("i32");
        } else if (numeric instanceof Long) {
          flags.append("i64");
        } else if (numeric instanceof Float) {
          flags.append("f32");
        } else if (numeric instanceof Double) {
          flags.append("f64");
        } else if (numeric instanceof Short) {
          flags.append("i16");
        } else if (numeric instanceof Byte) {
          flags.append("i08");
        } else if (numeric instanceof BigDecimal) {
          flags.append("b^d");
        } else if (numeric instanceof BigInteger) {
          flags.append("b^i");
        } else {
          flags.append("???");
        }
      }
    } else {
      flags.append("----");
    }
    if (info.hasDocValues()) {
      flags.append("D");
      flags.append(dvToString(info.getDocValuesType()));
    } else {
      flags.append("----");
    }    
    return flags.toString();
  }
  
  private static String dvToString(DocValuesType type) {
    String fl;
    if (type == null) {
      return "???";
    }
    switch (type) {
    case NUMERIC:
      fl = "num";
      break;
    case BINARY:
      fl = "bin";
      break;
    case SORTED:
      fl = "srt";
      break;
    case SORTED_SET:
      fl = "srtset";
      break;
    default:
      fl = "???";
    }
    return fl;
  }
  
  public static Resolution getResolution(String key) {
    if (key == null || key.trim().length() == 0) {
      return Resolution.MILLISECOND;
    }
    Resolution r = resolutionMap.get(key);
    if (r != null) return r;
    return Resolution.MILLISECOND;
  }

  private static HashMap<String, Resolution> resolutionMap = new HashMap<String, Resolution>();
  static {
    resolutionMap.put(Resolution.MILLISECOND.toString(), Resolution.MILLISECOND);
    resolutionMap.put(Resolution.SECOND.toString(), Resolution.SECOND);
    resolutionMap.put(Resolution.MINUTE.toString(), Resolution.MINUTE);
    resolutionMap.put(Resolution.HOUR.toString(), Resolution.HOUR);
    resolutionMap.put(Resolution.DAY.toString(), Resolution.DAY);
    resolutionMap.put(Resolution.MONTH.toString(), Resolution.MONTH);
    resolutionMap.put(Resolution.YEAR.toString(), Resolution.YEAR);
  }
  
  public static long calcTotalFileSize(String path, Directory fsdir) throws Exception {
    long totalFileSize = 0L;
    String[] files = null;
    files = fsdir.listAll();
    if (files == null) return totalFileSize;
    for (int i = 0; i < files.length; i++) {
      totalFileSize += fsdir.fileLength(files[i]);
    }
    return totalFileSize;
  }

  public static String normalizeUnit(long len) {
    if (len == 1) {
      return "  B";
    } else if (len < 1024) {
      return "  B";
    } else if (len < 51200000) {
      return " kB";
    } else {
      return " MB";
    }
  }

  public static String normalizeSize(long len) {
    if (len < 1024) {
      return String.valueOf(len);
    } else if (len < 51200000) {
      return String.valueOf(len / 1024);
    } else {
      return String.valueOf(len / 1048576);
    }
  }
}
