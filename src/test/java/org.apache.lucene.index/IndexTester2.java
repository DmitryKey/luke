package org.apache.lucene.index;

import junit.framework.TestCase;
import org.apache.lucene.analysis.standard.UAX29URLEmailAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.Version;
import org.getopt.luke.DocReconstructor;
import org.getopt.luke.IndexInfo;

import java.io.File;

/**
 * Created by cbamford on 19/11/2019.
 * Tests that unstored fields with no position info are reconstructed correctly.
 * For completeness it also checks the 3 other field types.
 */
public class IndexTester2 extends TestCase {

    private String indexPath = "src/test/indices/lukeindex2";
    private IndexWriterConfig indexCfg;
    private Directory directory;
    private DocReconstructor recon;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        directory = NIOFSDirectory.open(new File(indexPath));
        populate();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (directory != null) directory.close();
    }

    public void testDummy() {
        assertTrue(true == true);
    }

    public void testVerifyReconstructionOfMultipleFieldTypesAcrossMultipleDocs() throws Exception {

        // Check doc 1
        DocReconstructor.Reconstructed reconstructed = recon.reconstruct(0);
        assertEquals("value1", (reconstructed.getStoredFields().get("stored"))[0].stringValue());
        assertEquals("value1", reconstructed.getReconstructedFields().get("stored+tvs").get(0));
        assertEquals("value1", reconstructed.getReconstructedFields().get("unstored-posns").get(0));
        assertEquals("value1", reconstructed.getReconstructedFields().get("unstored+posns").get(0));

        // Check doc 2
        reconstructed = recon.reconstruct(1);
        assertEquals("value2", (reconstructed.getStoredFields().get("stored"))[0].stringValue());
        assertEquals("value2", reconstructed.getReconstructedFields().get("stored+tvs").get(0));
        assertEquals("value2", reconstructed.getReconstructedFields().get("unstored-posns").get(0));
        assertEquals("value2", reconstructed.getReconstructedFields().get("unstored+posns").get(0));
    }

    private void populate() throws Exception {
        // create an index
        indexCfg = new IndexWriterConfig(Version.LUCENE_4_10_3, new UAX29URLEmailAnalyzer());
        indexCfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        IndexWriter writer = new IndexWriter(directory, indexCfg);
        FieldType tvFtype = createUnstoredWithTermVectorsFieldType();

        Document doc = new Document();
        doc.add(new TextField("stored", "value1", Field.Store.YES));
        doc.add(new Field("stored+tvs", "value1", tvFtype));
        doc.add(new TextField("unstored+posns", "value1", Field.Store.NO));
        doc.add(new StringField("unstored-posns", "value1", Field.Store.NO));
        writer.addDocument(doc);

        doc = new Document();
        doc.add(new TextField("stored", "value2", Field.Store.YES));
        doc.add(new Field("stored+tvs", "value2", tvFtype));
        doc.add(new TextField("unstored+posns", "value2", Field.Store.NO));
        doc.add(new StringField("unstored-posns", "value2", Field.Store.NO));
        writer.addDocument(doc);

        writer.close();

        IndexReader ir = DirectoryReader.open(directory);
        IndexInfo idxInfo = new IndexInfo(ir, indexPath);
        String[] idxFields = idxInfo.getFieldNames().toArray(new String[0]);

        recon = new DocReconstructor(ir, idxFields, idxInfo.getNumTerms());
    }

    private FieldType createUnstoredWithTermVectorsFieldType() {
        FieldType fType = new FieldType();
        fType.setStored(false);
        fType.setIndexed(true);
        fType.setTokenized(true);
        fType.setIndexOptions(FieldInfo.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        fType.setStoreTermVectors(true);
        fType.setStoreTermVectorOffsets(true);
        fType.setStoreTermVectorPositions(true);
        return fType;
    }
}
