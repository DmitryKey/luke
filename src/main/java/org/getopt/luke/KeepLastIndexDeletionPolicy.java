package org.getopt.luke;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexDeletionPolicy;

public class KeepLastIndexDeletionPolicy extends IndexDeletionPolicy {

  /**
   * Deletes all commits except the most recent one.
   */
  public void onInit(List commits) {
    //System.out.println("onInit -> onCommit");
    // Note that commits.size() should normally be 1:
	  try {
		onCommit(commits);
	} catch (IOException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}
  }

  /**
   * Deletes all commits except the most recent one.
   */
  public void onCommit(List commits) throws IOException {
    //System.out.println("onCommit: " + commits);
    // Note that commits.size() should normally be 2 (if not
    // called by onInit above):
    int size = commits.size();
    for(int i=0;i<size-1;i++) {
      ((IndexCommit) commits.get(i)).delete();
    }
  }
}
