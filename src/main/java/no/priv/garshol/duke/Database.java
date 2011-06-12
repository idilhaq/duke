
package no.priv.garshol.duke;

import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.io.File;
import java.io.IOException;

import org.apache.lucene.util.Version;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.index.IndexNotFoundException;

/**
 * Represents a ...
 */
public class Database {
  private Map<String, Property> properties;
  private Collection<Property> proplist; // duplicate to preserve order
  private Collection<Property> lookups; // subset of properties
  private Map<Property, QueryResultTracker> trackers;
  private String path;
  private IndexWriter iwriter;
  private Directory directory;
  private IndexSearcher searcher;
  private double threshold;
  private double thresholdMaybe;
  private Collection<MatchListener> listeners;
  private Analyzer analyzer;

  // at index ix, the minimum probability a record must have *before*
  // comparison with property ix, in order to have any chance of going
  // over the threshold.
  // private double[] minprob;
  
  public Database(String path, Collection<Property> props, double threshold,
                  double thresholdMaybe, boolean overwrite) {
    this.path = path;
    this.proplist = props;
    this.properties = new HashMap(props.size());
    this.threshold = threshold;
    this.thresholdMaybe = thresholdMaybe;
    this.listeners = new ArrayList();
    this.trackers = new HashMap(props.size());

    // register properties
    analyzer = new StandardAnalyzer(Version.LUCENE_CURRENT);
    for (Property prop : props) {
      properties.put(prop.getName(), prop);

      QueryParser parser = new QueryParser(Version.LUCENE_CURRENT,
                                           prop.getName(), analyzer);
      trackers.put(prop, new QueryResultTracker(prop, parser));
    }

    // analyze properties to find lookup set
    findLookupProperties();
      
    // we don't open the indexes here. Configuration does it before
    // handing the database to client code.
  }

  // FIXME: not really part of API. may delete
  public void openSearchers() throws CorruptIndexException, IOException { 
    searcher = new IndexSearcher(directory, true);
  }
  
  public void addMatchListener(MatchListener listener) {
    listeners.add(listener);
  }
  
  public Collection<Property> getProperties() {
    return proplist;
  }

  public Collection<Property> getLookupProperties() {
    return lookups;
  }

  public Collection<Property> getIdentityProperties() {
    Collection<Property> ids = new ArrayList();
    for (Property p : getProperties())
      if (p.isIdProperty())
        ids.add(p);
    return ids;
  }

  public Property getPropertyByName(String name) {
    return properties.get(name);
  }

  public double getThreshold() {
    return threshold;
  }

  public double getMaybeThreshold() {
    return thresholdMaybe;
  }

  /**
   * Returns true iff the Lucene index is held in memory rather than
   * on disk.
   */
  public boolean isInMemory() {
    return (directory instanceof RAMDirectory);
  }

  /**
   * Add the record to the index.
   */
  public void index(Record record) throws CorruptIndexException, IOException {
    Document doc = new Document();

    for (String propname : record.getProperties()) {
      // FIXME: we assume just one value. not sure yet if this is safe.
      String value = record.getValue(propname);
      if (value == null || value.equals(""))
        continue; // FIXME: not sure if this is necessary
      
      Property prop = getPropertyByName(propname);
      if (prop == null)
        throw new DukeConfigException("Record has property " + propname +
                                      " for which there is no configuration");
      
      Field.Index ix; // FIXME: could cache this. or get it from property
      if (prop.isIdProperty())
        ix = Field.Index.ANALYZED; // so findRecordById will work
      else if (prop.isAnalyzedProperty())
        ix = Field.Index.ANALYZED;
      else
        ix = Field.Index.NOT_ANALYZED;

      doc.add(new Field(propname, value, Field.Store.YES, ix));
    }

    iwriter.addDocument(doc);
  }

  /**
   * Flushes all changes to disk.
   */
  public void commit() throws CorruptIndexException, IOException {
    if (searcher != null)
      searcher.close();
    iwriter.optimize();
    iwriter.commit();
    searcher = new IndexSearcher(directory, true);
  }

  /**
   * Look up record by identity.
   */
  public Record findRecordById(String id) {
    // FIXME: assume exactly one ID property
    // FIXME: a bit much code duplication here
    Property idprop = getIdentityProperties().iterator().next();
    QueryResultTracker tracker = trackers.get(idprop);

    for (Record r : tracker.lookup(id))
      if (r.getValue(idprop.getName()).equals(id))
        return r;

    return null; // not found
  }
  
  /**
   * Look up potentially matching records for this property value.
   */
  public Collection<Record> lookup(Property prop, Collection<String> values) {
    if (values == null || values.isEmpty())
      return Collections.EMPTY_SET;

    // true => read-only. must reopen every time to see latest changes to
    // index.
    Comparator comp = prop.getComparator();
    QueryResultTracker tracker = trackers.get(prop);

    // FIXME: this algorithm is clean, but has suboptimal performance.
    Collection<Record> matches = new ArrayList();
    for (String value : values)
      matches.addAll(tracker.lookup(value));
    
    return matches;
  }
  
  /**
   * Removes the record with the given ID from the index, if it's there.
   * If the record is not there this has no effect.
   */
  public void unindex(String id) {
  }

  /**
   * Removes all equality statements about this id, if any.
   */
  public void removeEqualities(String id) {
  }

  /**
   * Notifies listeners that we started on this record.
   */
  public void startRecord(Record record) {
    for (MatchListener listener : listeners)
      listener.startRecord(record);
  }
  
  /**
   * Records the statement that the two records match.
   */
  public void registerMatch(Record r1, Record r2, double confidence) {
    for (MatchListener listener : listeners)
      listener.matches(r1, r2, confidence);
  }

  /**
   * Records the statement that the two records may match.
   */
  public void registerMatchPerhaps(Record r1, Record r2, double confidence) {
    for (MatchListener listener : listeners)
      listener.matchesPerhaps(r1, r2, confidence);
  }

  /**
   * Notifies listeners that we finished this record.
   */
  public void endRecord() {
    for (MatchListener listener : listeners)
      listener.endRecord();
  }
  
  /**
   * Stores state to disk and closes all open resources.
   */
  public void close() throws CorruptIndexException, IOException {
    iwriter.close();
    directory.close();
    if (searcher != null)
      searcher.close();
  }

  // ----- INTERNALS

  private void findLookupProperties() {
    // sort properties by high probability
    List<Property> candidates = new ArrayList();
    for (Property prop : properties.values())
      if (!prop.isIdProperty())
        candidates.add(prop);

    Collections.sort(candidates, new HighComparator());

    // find the lookup properties
    int ix;
    double prob = 0.5;    
    for (ix = 0; ix < candidates.size(); ix++) {
      prob = Utils.computeBayes(prob, candidates.get(ix).getHighProbability());
      if (prob >= threshold)
        break;
    }

    if (prob < threshold)
      throw new DukeConfigException("Maximum possible probability is " + prob +
                                 ", which is below threshold (" + threshold +
                                 "), which means no duplicates will ever " +
                                 "be found");
    
    lookups = new ArrayList(candidates.subList(ix, candidates.size()));

    // analyze again, to see what probability must be *before* each
    // property so that we can break off processing of records whose
    // probabilities are so low that they cannot get above the threshold
    // even if all remaining properties match.
    // minprob = new double[candidates.size()];
    // for (ix = candidates.size() - 1; ix >= 0; ix--) {
    //   double mustgetto;
    //   if (ix + 1 == candidates.size())
    //     mustgetto = threshold;
    //   else
    //     mustgetto = minprob[ix + 1];

    //   double high = candidates.get(ix).getHighProbability();

// NOTE: I tried to work out the equation for doing Bayes backwards, but
// must have gotten it wrong somehow. it certainly doesn't produce the
// right results. will return to this and work on it later. probably
// best to do the equations on paper.
      
// mustgetto = bayes(high, min)

// mustgetto = 
//     (high * min) /
//       ((high * min) + ((1.0 - high) * (1.0 - min)))

// 1 / mustgetto =
//   ((high * min) + ((1.0 - high) * (1.0 - min))) / (high * min)

// (high * min) / mustgetto =
//   (high * min) + ((1.0 - high) * (1.0 - min))

// (high * min) / mustgetto - ((1.0 - high) * (1.0 - min)) = (high * min)

// 1 / mustgetto - ((1.0 - high) * (1.0 - min)) = 1

// (1.0 - high) * (1.0 - min) = 1 - (1 / mustgetto)

// 1.0 - min = (1 - (1 / mustgetto)) / (1.0 - high)

// - min = (1 - (1 / mustgetto)) / (1.0 - high) - 1.0

// min = 1.0 - (1 - (1.0 / mustgetto)) / (1.0 - high)      
//    }
  }

  private class HighComparator implements java.util.Comparator<Property> {
    public int compare(Property p1, Property p2) {
      if (p1.getHighProbability() < p2.getHighProbability())
        return -1;
      else if (p1.getHighProbability() == p2.getHighProbability())
        return 0;
      else
        return 1;
    }
  }

  // called by Configuration.getDatabase
  public void openIndexes(boolean overwrite) {
    if (directory == null) {
      try {
        if (path == null)
          directory = new RAMDirectory();
        else
          directory = FSDirectory.open(new File(path));
        iwriter = new IndexWriter(directory, analyzer, overwrite,
                                  new IndexWriter.MaxFieldLength(25000));
        iwriter.commit(); // so that the searcher doesn't fail
      } catch (IndexNotFoundException e) {
        if (!overwrite) {
          // the index was not there, so make a new one
          directory = null; // ensure we really do try again
          openIndexes(true);
        }
        else
          throw new RuntimeException(e);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * These objects are used to estimate the size of the query result
   * we should ask Lucene for. This parameter is the single biggest
   * influence on matching performance, but setting it too low causes
   * matches to be missed. We therefore try hard to estimate it as
   * correctly as possible.
   *
   * <p>The reason this is a separate class is that we need one of
   * these for every property because the different properties will
   * behave differently.
   *
   * <p>FIXME: the class is badly named.
   */
  class QueryResultTracker {
    private Property property;
    private QueryParser parser;
    private int limit;
    /**
     * Ring buffer containing n last search result sizes, except for
     * searches which found nothing.
     */
    private int[] prevsizes;
    private int sizeix; // position in prevsizes

    public QueryResultTracker(Property property, QueryParser parser) {
      this.property = property;
      this.parser = parser;
      this.limit = 10;
      this.prevsizes = new int[10];
    }

    public Collection<Record> lookup(String value) {
      String v = cleanLucene(value);
      if (v.length() == 0)
        return Collections.EMPTY_SET;

      List<Record> matches = new ArrayList(limit);
      try {
        Query query = parser.parse(v);
        ScoreDoc[] hits;

        int thislimit = limit;
        while (true) {
          hits = searcher.search(query, null, thislimit).scoreDocs;
          if (hits.length < thislimit)
            break;
          thislimit = thislimit * 5;
        }
        
        for (int ix = 0; ix < hits.length; ix++)
          matches.add(new DocumentRecord(hits[ix].doc,
                                         searcher.doc(hits[ix].doc)));

        if (hits.length > 0) {
          prevsizes[sizeix++] = hits.length;
          if (sizeix == prevsizes.length) {
            sizeix = 0;
            limit = Math.max((int) (average() * 4), limit);
          }
          // System.out.println(property.getName() + ": " +
          //                    hits.length + " -> " +
          //                    average() + " (" + limit + ")");

          // for (int ix = Math.max(0, matches.size() - 10); ix < matches.size(); ix++) {
          //   Record r = matches.get(ix);
          //   double high = 0.0;
          //   for (String val : r.getValues(property.getName()))
          //     high = Math.max(high, comp.compare(value, val));
          //   System.out.println("  " + ix + " -> " + high);
          // }
        }
        
      } catch (IOException e) {
        throw new RuntimeException(e);
      } catch (ParseException e) {
        throw new RuntimeException(e); // should be impossible
      }
      return matches;
    }

    private double average() {
      int sum = 0;
      int ix = 0;
      for (; ix < prevsizes.length && prevsizes[ix] != 0; ix++)
        sum += prevsizes[ix];
      return sum / (double) ix;
    }
        
    private String cleanLucene(String query) {
      char[] tmp = new char[query.length()];
      int count = 0;
      for (int ix = 0; ix < query.length(); ix++) {
        char ch = query.charAt(ix);
        if (ch != '*' && ch != '?' && ch != '!' && ch != '&' && ch != '(' &&
            ch != ')' && ch != '-' && ch != '+' && ch != ':' && ch != '"' &&
            ch != '[' && ch != ']' && ch != '~' && ch != '{' && ch != '}' &&
            ch != '^' && ch != '|')
          tmp[count++] = ch;
      }
      
      return new String(tmp, 0, count).trim();
    }
  }
}