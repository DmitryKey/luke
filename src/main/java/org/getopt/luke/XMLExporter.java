package org.getopt.luke;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.getopt.luke.decoders.Decoder;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

public class XMLExporter extends Observable {
  private AtomicReader atomicReader = null;
  private IndexReader indexReader;
  private String indexPath;
  private boolean abort = false;
  private boolean running = false;
  private boolean decode = false;
  private ProgressNotification pn = new ProgressNotification();
  private List<String> fieldNames;
  private Map<String,Decoder> decoders;
  private FieldInfos infos;
  
  public XMLExporter(IndexReader indexReader, String indexPath,
          Map<String, Decoder> decoders) throws IOException {
    this.indexReader = indexReader;
    if (indexReader instanceof CompositeReader) {
      this.atomicReader = SlowCompositeReaderWrapper.wrap(indexReader);
    } else if (indexReader instanceof AtomicReader) {
      this.atomicReader =  (AtomicReader)indexReader;
    }
    if (this.atomicReader != null) {
      infos = atomicReader.getFieldInfos();
    }
    this.indexPath = indexPath;
    this.decoders = decoders;
    // dump in predictable order
    fieldNames = new ArrayList<String>();
    fieldNames.addAll(Util.fieldNames(indexReader, false));
    Collections.sort(fieldNames);
  }
  
  public void abort() {
    abort = true;
  }
  
  public boolean isAborted() {
    return abort;
  }
  
  public boolean exportJS(String outputFile, boolean decode, boolean gzip, boolean preamble, boolean info,
          String rootElementName) throws Exception {
    OutputStream out;
    if (gzip) {
      out = new GZIPOutputStream(new FileOutputStream(outputFile));
    } else {
      out = new FileOutputStream(outputFile);
    }
    return export(out, decode, preamble, info, rootElementName, null);
  }
  
  /**
   * 
   * @param output output stream
   * @param decode use defined field value decoders
   * @param preamble include XML preamble
   * @param info include index info section
   * @param rootElementName name of the root XML elements
   * @param ranges if non-null then export only these ranges of documents
   * @return
   * @throws Exception
   */
  public boolean export(OutputStream output, boolean decode, boolean preamble, boolean info,
      String rootElementName, Ranges ranges) throws Exception {
    running = true;
    pn.message = "Export running ...";
    pn.minValue = 0;
    pn.maxValue = atomicReader.maxDoc();
    pn.curValue = 0;
    setChanged();
    notifyObservers(pn);
    if (rootElementName == null) {
      rootElementName = "index";
    }
    if (decoders == null || decoders.isEmpty()) {
      decode = false;
    }
    BufferedWriter bw;
    boolean rootWritten = false;
    int delta = atomicReader.maxDoc() / 100;
    if (delta == 0) delta = 1;
    int cnt = 0;
    bw = new BufferedWriter(new OutputStreamWriter(output, "UTF-8"));
    Bits live = atomicReader.getLiveDocs();
    try {
      // write out XML preamble
      if (preamble) {
        bw.write("<?xml version='1.0' encoding='UTF-8'?>\n");
      }
      bw.write("<" + rootElementName + ">\n");
      rootWritten = true;
      if (info) {
        // write out some statistics
        writeIndexInfo(bw);
      }
      Document doc = null;
      int i = -1;
      if (ranges == null) {
        ranges = new Ranges();
        ranges.set(0, atomicReader.maxDoc());
      }
      if (ranges.cardinality() > 0) {
        while ( (i = ranges.nextSetBit(++i)) != -1) {
          if (i >= atomicReader.maxDoc()) {
            break;
          }
          if (abort) {
            pn.message = "User requested abort.";
            pn.aborted = true;
            running = false;
            setChanged();
            notifyObservers(pn);
            break;
          }
          if (live != null && !live.get(i)) continue; // skip deleted docs
          doc = atomicReader.document(i);
          // write out fields
          writeDoc(bw, i, doc, decode, live);
          pn.curValue = i + 1;
          cnt++;
          if (cnt > delta) {
            cnt = 0;
            setChanged();
            notifyObservers(pn);
          }
        }
      }
    } catch (Exception ioe) {
      ioe.printStackTrace();
      pn.message = "ERROR creating output: " + ioe.toString();
      pn.aborted = true;
      running = false;
      setChanged();
      notifyObservers(pn);
      return false;
    } finally {
      if (bw != null) {
        try {
          if (rootWritten) { // balance the top tag
            bw.write("</" + rootElementName + ">");
          }
          bw.flush();
        } catch (Exception e) {
          pn.message = "ERROR closing output: " + e.toString();
          pn.aborted = true;
          running = false;
          setChanged();
          notifyObservers(pn);
          return false;
        }
      }
    }
    pn.message = "Finished.";
    setChanged();
    notifyObservers(pn);
    running = false;
    return !pn.aborted;
  }
  
  private void writeDoc(BufferedWriter bw, int docNum, Document doc, boolean decode,
          Bits liveDocs) throws Exception {
    bw.write("<doc id='" + docNum + "'>\n");
    BytesRef bytes = new BytesRef();
    for (String fieldName : fieldNames) {
      IndexableField[] fields = doc.getFields(fieldName);
      if (fields == null || fields.length == 0) {
        continue;
      }
      bw.write("<field name='" + Util.xmlEscape(fields[0].name()));
      NumericDocValues dv = atomicReader.getNormValues(fields[0].name());
      if (dv != null) {
        // export raw value - we don't know what similarity was used
        String type = dv.toString();
        if (type.contains("INT")) {
          bw.write("' norm='" + dv.get(docNum));
        } else if (type.startsWith("FLOAT")) {
            bw.write("' norm='" + dv.get(docNum));
        } else if (type.startsWith("BYTES")) {
          bw.write("' norm='" + dv.get(docNum));
          bw.write("' norm='" + Util.bytesToHex(bytes, false));
        }
      } 
      bw.write("' flags='" + Util.fieldFlags((Field)fields[0], infos.fieldInfo(fields[0].name())) + "'>\n");
      for (IndexableField ixf : fields) {
        String val = null;
        Field f = (Field)ixf;
        if (decode) {
          Decoder d = decoders.get(f.name());
          if (d != null) {
            val = d.decodeStored(f.name(), f);
          }
        }
        if (!decode || val == null) {
          if (f.binaryValue() != null) {
            val = Util.bytesToHex(f.binaryValue(), false);
          } else {
            val = f.stringValue();
          }
        }
        bw.write("<val>" + Util.xmlEscape(val) + "</val>\n");
      }
      Terms tfv = atomicReader.getTermVector(docNum, fieldName);
      if (tfv != null) {
        writeTermVector(bw, tfv, liveDocs);
      }
      bw.write("</field>\n");
    }
    bw.write("</doc>\n");
  }
  
  private void writeTermVector(BufferedWriter bw, Terms tfv, Bits liveDocs) throws Exception {
    bw.write("<tv>\n");
    TermsEnum te = tfv.iterator(null);
    DocsAndPositionsEnum dpe = null;
    StringBuilder positions = new StringBuilder();
    StringBuilder offsets = new StringBuilder();
    while (te.next() != null) {
      // collect
      positions.setLength(0);
      offsets.setLength(0);
      DocsAndPositionsEnum newDpe = te.docsAndPositions(liveDocs, dpe,
              1);
      if (newDpe == null) {
        continue;
      }
      dpe = newDpe;
      // there's only at most one doc here, so position the enum
      if (dpe.nextDoc() == DocsEnum.NO_MORE_DOCS) {
        continue;
      }
      for (int k = 0; k < dpe.freq(); k++) {
        int pos = dpe.nextPosition();
        if (pos != -1) { // has positions
          if (positions.length() > 0) positions.append(' ');
          positions.append(String.valueOf(pos));
        }
        if (dpe.startOffset() != -1) { // has offsets
          if (offsets.length() > 0) offsets.append(' ');
          offsets.append(dpe.startOffset() + "-" + dpe.endOffset());
        }
      }
      bw.write("<t text='" + Util.xmlEscape(te.term().utf8ToString()) + "' freq='" + dpe.freq() + "'");
      if (positions.length() > 0) {
        bw.write(" positions='" + positions.toString() + "'");
      }
      if (offsets.length() > 0) {
        bw.write(" offsets='" + offsets.toString() + "'");
      }
      bw.write("/>\n");
    }
    bw.write("</tv>\n");
  }
  
  private void writeIndexInfo(BufferedWriter bw) throws Exception {
    bw.write("<info>\n");
    IndexInfo indexInfo = new IndexInfo(indexReader, indexPath);
    bw.write(" <indexPath>" + Util.xmlEscape(indexPath) + "</indexPath>\n");
    bw.write(" <fields count='" + indexInfo.getFieldNames().size() + "'>\n");
    for (String fname : indexInfo.getFieldNames()) {
      bw.write("  <field name='" + Util.xmlEscape(fname) + "'/>\n");
    }
    bw.write(" </fields>\n");
    bw.write(" <numDocs>" + atomicReader.numDocs() + "</numDocs>\n");
    bw.write(" <maxDoc>" + atomicReader.maxDoc() + "</maxDoc>\n");
    bw.write(" <numDeletedDocs>" + atomicReader.numDeletedDocs() + "</numDeletedDocs>\n");
    bw.write(" <numTerms>" + indexInfo.getNumTerms() + "</numTerms>\n");
    bw.write(" <hasDeletions>" + atomicReader.hasDeletions() + "</hasDeletions>\n");
    bw.write(" <lastModified>" + indexInfo.getLastModified() + "</lastModified>\n");
    bw.write(" <indexVersion>" + indexInfo.getVersion() + "</indexVersion>\n");
    bw.write(" <indexFormat>\n");
    bw.write("  <genericName>" + indexInfo.getIndexFormat().genericName + "</genericName>\n");
    bw.write("  <capabilities>" + indexInfo.getIndexFormat().capabilities + "</capabilities>\n");
    bw.write(" </indexFormat>\n");
    bw.write(" <directoryImpl>" + indexInfo.getDirImpl() + "</directoryImpl>\n");
    Directory dir = indexInfo.getDirectory();
    if (dir != null) {
      bw.write(" <files count='" + dir.listAll().length + "'>\n");
      String[] files = dir.listAll();
      Arrays.sort(files);
      for (String file : files) {
        bw.write("  <file name='" + file +
            "' size='" + dir.fileLength(file) +
            "' func='" + IndexGate.getFileFunction(file) + "'/>\n");
      }
      bw.write(" </files>\n");
      List<IndexCommit> commits = DirectoryReader.listCommits(dir);
      bw.write(" <commits count='" + commits.size() + "'>\n");
      for (IndexCommit ic : commits) {
        bw.write("  <commit segment='" + ic.getSegmentsFileName() + "' segCount='" + ic.getSegmentCount() + 
            "' deleted='" + ic.isDeleted() + "' files='" + ic.getFileNames().size() + "'>\n");
        for (Object p : ic.getFileNames()) {
          bw.write("   <file name='" + p.toString() + "'/>\n");
        }
        Map<String,String> userData = ic.getUserData();
        if (userData != null && userData.size() > 0) {
          bw.write("   <userData size='" + userData.size() + "'>" + userData.toString() + "</userData>\n");
        }
        bw.write("  </commit>\n");
      }
      bw.write(" </commits>\n");
    }
    TermStats[] topTerms = indexInfo.getTopTerms();
    if (topTerms != null) {
      bw.write(" <topTerms count='" + topTerms.length + "'>\n");
      for (TermStats ts : topTerms) {
        String val = null;
        if (decode) {
          Decoder d = decoders.get(ts.field);
          if (d != null) {
            val = d.decodeTerm(ts.field, ts.termtext);
          }
        }
        if (!decode || val == null) {
          val = ts.termtext.utf8ToString();
        }
        val = Util.xmlEscape(val);
        bw.write("  <term field='" + Util.xmlEscape(ts.field) + "' text='" +
                val +
          "' docFreq='" + ts.docFreq + "'/>\n");
      }
    }
    bw.write(" </topTerms>\n");
    bw.write("</info>\n");    
  }

  /**
   * @return the running
   */
  public boolean isRunning() {
    return running;
  }
  
  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("Usage: XMLExporter <indexPath> <outputFile> [-gzip] [-onlyInfo] [-range ..expr..]");
      System.err.println("\tindexPath\tname of the directory containing the index");
      System.err.println("\toutputFile\toutput file, or '-' for System.out");
      System.err.println("\tgzip\tcompress output using gzip compression");
      System.err.println("\tonlyInfo\texport only the overall information about the index");
      System.err.println("\trange\tspecify ranges of documents to export. Expressions cannot contain whitespace!");
      System.err.println("\t\tExample: 0-5,15,32-100,101,103,105-500");
      System.exit(-1);
    }
    Directory dir = FSDirectory.open(new File(args[0]));
    if (!DirectoryReader.indexExists(dir)) {
      throw new Exception("There is no valid Lucene index here: '" + args[0] + "'");
    }
    File out = null;
    if (!args[1].equals("-")) {
      out = new File(args[1]);
    }
    if (out != null && out.exists()) {
      throw new Exception("Output file already exists: '" + out.getAbsolutePath() + "'");
    }
    boolean gzip = false;
    Ranges ranges = null;
    boolean onlyInfo = false;
    for (int i = 2; i < args.length; i++) {
      if (args[i].equals("-gzip")) {
        gzip = true;
      } else if (args[i].equals("-range")) {
        ranges = Ranges.parse(args[++i]);
      } else if (args[i].equals("-onlyInfo")) {
        onlyInfo = true;
      } else {
        throw new Exception("Unknown argument: '" + args[i] + "'");
      }
    }
    DirectoryReader reader = DirectoryReader.open(dir);
    XMLExporter exporter = new XMLExporter(reader, args[0], null);
    OutputStream os;
    if (out == null) {
      os = System.out;
    } else {
      os = new FileOutputStream(out);
    }
    if (gzip) {
      os = new GZIPOutputStream(os);
    }
    if (onlyInfo) {
      ranges = new Ranges();
    }
    exporter.export(os, false, false, true, "index", ranges);
    os.flush();
    os.close();
    System.exit(0);
  }
}
