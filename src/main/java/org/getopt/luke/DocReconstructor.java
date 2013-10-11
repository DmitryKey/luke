package org.getopt.luke;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.util.Bits;

import java.util.*;

/**
 * This class attempts to reconstruct all fields from a document
 * existing in a Lucene index. This operation may be (and usually) is
 * lossy - e.g. unstored fields are rebuilt from terms present in the
 * index, and these terms may have been changed (e.g. lowercased, stemmed),
 * and many other input tokens may have been skipped altogether by the
 * Analyzer, when fields were originally added to the index.
 * 
 * @author ab
 *
 */
public class DocReconstructor extends Observable {
  private ProgressNotification progress = new ProgressNotification();
  private String[] fieldNames = null;
  private AtomicReader reader = null;
  private int numTerms;
  private Bits live;
  
  /**
   * Prepare a document reconstructor.
   * @param reader IndexReader to read from.
   * @throws Exception
   */
  public DocReconstructor(IndexReader reader) throws Exception {
    this(reader, null, -1);
  }
  
  /**
   * Prepare a document reconstructor.
   * @param reader IndexReader to read from.
   * @param fieldNames if non-null or not empty, data will be collected only from
   * these fields, otherwise data will be collected from all fields
   * @param numTerms total number of terms in the index, or -1 if unknown (will
   * be calculated)
   * @throws Exception
   */
  public DocReconstructor(IndexReader reader, String[] fieldNames, int numTerms) throws Exception {
    if (reader == null) {
      throw new Exception("IndexReader cannot be null.");
    }
    if (reader instanceof CompositeReader) {
      this.reader = SlowCompositeReaderWrapper.wrap(reader);
    } else if (reader instanceof AtomicReader) {
      this.reader = (AtomicReader)reader;
    } else {
      throw new Exception("Unsupported IndexReader class " + reader.getClass().getName());
    }
    if (fieldNames == null || fieldNames.length == 0) {
      // collect fieldNames
      this.fieldNames = Util.fieldNames(reader, false).toArray(new String[0]);
    } else {
      this.fieldNames = fieldNames;
    }
    if (numTerms == -1) {
      Fields fields = MultiFields.getFields(reader);
      numTerms = 0;
      Iterator<String> fe = fields.iterator();
      while (fe.hasNext()) {
          String fld = fe.next();
        Terms t = fields.terms(fld);
        TermsEnum te = t.iterator(null);
        while (te.next() != null) {
          numTerms++;
        }
      }
      this.numTerms = numTerms;
    }
    live = MultiFields.getLiveDocs(reader);
  }
  
  /**
   * Reconstruct document fields.
   * @param docNum document number. If this document is deleted, but the index
   * is not optimized yet, the reconstruction process may still yield the
   * reconstructed field content even from deleted documents.
   * @return reconstructed document
   * @throws Exception
   */
  public Reconstructed reconstruct(int docNum) throws Exception {
    if (docNum < 0 || docNum > reader.maxDoc()) {
      throw new Exception("Document number outside of valid range.");
    }
    Reconstructed res = new Reconstructed();
    if (live != null && !live.get(docNum)) {
      throw new Exception("Document is deleted.");
    } else {
      Document doc =  reader.document(docNum);
      for (int i = 0; i < fieldNames.length; i++) {
        IndexableField[] fs = doc.getFields(fieldNames[i]);
        if (fs != null && fs.length > 0) {
          res.getStoredFields().put(fieldNames[i], fs);
        }
      }
    }
    // collect values from unstored fields
    HashSet<String> fields = new HashSet<String>(Arrays.asList(fieldNames));
    // try to use term vectors if available
    progress.maxValue = fieldNames.length;
    progress.curValue = 0;
    progress.minValue = 0;
    TermsEnum te = null;
    DocsAndPositionsEnum dpe = null;
    for (int i = 0; i < fieldNames.length; i++) {
      Terms tvf = reader.getTermVector(docNum, fieldNames[i]);
      if (tvf != null) { // has vectors for this field
        te = tvf.iterator(te);
        progress.message = "Checking term vectors for '" + fieldNames[i] + "' ...";
        progress.curValue = i;
        setChanged();
        notifyObservers(progress);
        List<IntPair> vectors = TermVectorMapper.map(tvf, te, false, true);
        if (vectors != null) {
          GrowableStringArray gsa = res.getReconstructedFields().get(fieldNames[i]);
          if (gsa == null) {
            gsa = new GrowableStringArray();
            res.getReconstructedFields().put(fieldNames[i], gsa);
          }
          for (IntPair ip : vectors) {
            for (int m = 0; m < ip.positions.length; m++) {
              gsa.append(ip.positions[m], "|", ip.text);
            }
          }
          fields.remove(fieldNames[i]); // got what we wanted
        }
      }
    }
    // this loop collects data only from left-over fields
    // not yet collected through term vectors
    progress.maxValue = fields.size();
    progress.curValue = 0;
    progress.minValue = 0;
    for (String fld : fields) {
      progress.message = "Collecting terms in " + fld + " ...";
      progress.curValue++;
      setChanged();
      notifyObservers(progress);
      Terms terms = MultiFields.getTerms(reader, fld);
      if (terms == null) { // no terms in this field
        continue;
      }
      te = terms.iterator(te);
      while (te.next() != null) {
        DocsAndPositionsEnum newDpe = te.docsAndPositions(live, dpe, 0);
        if (newDpe == null) { // no position info for this field
          break;
        }
        dpe = newDpe;
        int num = dpe.advance(docNum);
        if (num != docNum) { // either greater than or NO_MORE_DOCS
          continue; // no data for this term in this doc
        }
        String term = te.term().utf8ToString();
        GrowableStringArray gsa = (GrowableStringArray)
              res.getReconstructedFields().get(fld);
        if (gsa == null) {
          gsa = new GrowableStringArray();
          res.getReconstructedFields().put(fld, gsa);
        }
        for (int k = 0; k < dpe.freq(); k++) {
          int pos = dpe.nextPosition();
          gsa.append(pos, "|", term);
        }
      }
    }
    progress.message = "Done.";
    progress.curValue = 100;
    setChanged();
    notifyObservers(progress);
    return res;
  }
  
  /**
   * This class represents a reconstructed document.
   * @author ab
   */
  public static class Reconstructed {
    private Map<String, IndexableField[]> storedFields;
    private Map<String, GrowableStringArray> reconstructedFields;

    public Reconstructed() {
      storedFields = new HashMap<String, IndexableField[]>();
      reconstructedFields = new HashMap<String, GrowableStringArray>();
    }
    
    /**
     * Construct an instance of this class using existing field data.
     * @param storedFields field data of stored fields
     * @param reconstructedFields field data of unstored fields
     */
    public Reconstructed(Map<String, IndexableField[]> storedFields,
        Map<String, GrowableStringArray> reconstructedFields) {
      this.storedFields = storedFields;
      this.reconstructedFields = reconstructedFields;
    }
    
    /**
     * Get an alphabetically sorted list of field names.
     */
    public List<String> getFieldNames() {
      HashSet<String> names = new HashSet<String>();
      names.addAll(storedFields.keySet());
      names.addAll(reconstructedFields.keySet());
      ArrayList<String> res = new ArrayList<String>(names.size());
      res.addAll(names);
      Collections.sort(res);
      return res;
    }
    
    public boolean hasField(String name) {
      return storedFields.containsKey(name) || reconstructedFields.containsKey(name);
    }

    /**
     * @return the storedFields
     */
    public Map<String, IndexableField[]> getStoredFields() {
      return storedFields;
    }

    /**
     * @return the reconstructedFields
     */
    public Map<String, GrowableStringArray> getReconstructedFields() {
      return reconstructedFields;
    }

  }
}
