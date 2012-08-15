package ahrd.model;

import static ahrd.controller.Settings.getSettings;
import static ahrd.controller.Utils.zeroList;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ahrd.exception.MissingProteinException;

/**
 * We estimate protein similarity based on the formulae given in the article
 * "Protein comparison at the domain architecture level" by Lee and Lee
 * (http://www.biomedcentral.com/1471-2105/10/S15/S5). The more similar a
 * described protein is to the query the more appropriate its description. Hence
 * we introduce a new Domain-Score on the level of BlastResults to reflect this
 * concept.
 * 
 * @author hallab, bangalore, klee
 */
public class DomainScoreCalculator {

	/**
	 * Result from parsing SIMAP's concatenated and preprocessed feature-files.
	 * Preprocessing involves substitution of SIMAP-Hashes with the original
	 * Protein-Accessions.
	 * 
	 * @note: See awk-scripts in directory helper_scripts.
	 */
	private static Map<String, Set<String>> blastResultAccessionsToInterproIds = new HashMap<String, Set<String>>();

	/**
	 * To enable calculation of domain-architecture scores, we need to know the
	 * concrete architecture of proteins of significant similarity (BLAST
	 * results). In this memory database we store the Pfam domains.
	 */
	private static Map<String, Set<String>> blastResultAccessionsToPfamIds = new HashMap<String, Set<String>>();

	public static void initializeBlastResultAccessionsToInterproIds()
			throws IOException {
		// blastResultAccessionsToInterproIds = new HashMap<String,
		// Set<String>>();
		BufferedReader reader = new BufferedReader(new InputStreamReader(
				new FileInputStream(getSettings()
						.getPathToInterproResults4BlastHits())));
		String accession = "";
		String interproId = "";
		String line = null;
		while ((line = reader.readLine()) != null) {
			Pattern pn = Pattern.compile("(\\S+)\\s+.*\\s(IPR\\d{6})\\s.*");
			Matcher mr = pn.matcher(line);
			if (mr.matches()) {
				accession = mr.group(1);
				interproId = mr.group(2);

				if (!getBlastResultAccessionsToInterproIds().containsKey(
						accession)) {
					getBlastResultAccessionsToInterproIds().put(accession,
							new HashSet<String>());
				}
				getBlastResultAccessionsToInterproIds().get(accession).add(
						interproId);
			}
		}

	}

	private Protein protein;
	private SortedSet<String> vectorSpaceModel;
	private Map<String, Double> cumulativeTokenDomainSimilarityScores = new HashMap<String, Double>();
	private Double totalTokenDomainSimilarityScore = 0.0;

	/**
	 * 
	 * 1.) Construct the vector space model for the Protein and its BlastResults
	 * 2.) Construct the domain-weights vector for the Protein itself 3.) ...and
	 * all of its BlastResults
	 * 
	 * @param prot
	 */
	public static void constructDomainWeightVectors(Protein prot) {

		// Vector Space Model of all distinct annotated Interpro-Entities:
		SortedSet<String> vsm = constructVectorSpaceModel(prot);

		// Domain-Weight Vector for the Protein itself:
		List<Double> prVec = new Vector<Double>();
		for (Iterator<String> it = vsm.iterator(); it.hasNext();) {
			String ipr = it.next();
			InterproResult interproEntry = InterproResult.getInterproDb().get(
					ipr);
			double weight = interproEntry.getDomainWeight();

			if (prot.getInterproResults().contains(interproEntry)) {
				prVec.add(weight);
			} else
				prVec.add(0.0);
		}
		prot.setDomainWeights(prVec);

		// Domain-Weight Vector for all Protein's BlastResults:
		for (String blastDb : prot.getBlastResults().keySet()) {
			for (BlastResult br : prot.getBlastResults().get(blastDb)) {
				List<Double> brVec = zeroList(vsm.size());
				Set<String> brIprAnnotations = getBlastResultAccessionsToInterproIds()
						.get(br.getAccession());
				if (brIprAnnotations != null && brIprAnnotations.size() > 0) {
					int index = 0;
					for (String iprId : vsm) {
						InterproResult ipr = InterproResult.getInterproDb()
								.get(iprId);
						if (brIprAnnotations.contains(iprId) && ipr != null) {
							brVec.set(index, ipr.getDomainWeight());
						}
						index++;
					}
				}
				br.setDomainWeights(brVec);
			}
		}
	}

	/**
	 * Calculates the cosine of angle between the two argument vectors as a
	 * measure of their similarity: sim(x,y) = dot-product(x,y) / (||x||*||y||).
	 * 
	 * For any or both vectors equaling the origin, this function returns not
	 * NaN, but zero.
	 * 
	 * @param x
	 * @param y
	 * @return sim(x,y)
	 */
	public static Double domainWeightSimilarity(List<Double> prVec,
			List<Double> brVec) {
		Double dotProduct = 0.0;
		for (int i = 0; i < prVec.size(); i++) {
			dotProduct += (prVec.get(i) * brVec.get(i));
		}

		Double magPr = 0.0;
		Double magBr = 0.0;
		for (int i = 0; i < prVec.size(); i++) {
			magPr += Math.pow(prVec.get(i), 2);
			magBr += Math.pow(brVec.get(i), 2);
		}
		Double magnitude = Math.sqrt(magPr) * Math.sqrt(magBr);
		Double dws = dotProduct / magnitude;

		if (dws.equals(Double.NaN))
			dws = 0.0;

		return dws;
	}

	/**
	 * Gathers all distinct InterproIDs assigned to the Protein and its
	 * BlastResults, than constructs a sorted Set of them to be used as a
	 * definition for the vector space model.
	 * 
	 * @param Protein
	 *            prot
	 * @return SortedSet<String> of the respective InterproIDs in their natural
	 *         order
	 */
	public static SortedSet<String> constructVectorSpaceModel(Protein prot) {
		SortedSet<String> vectorSpaceModel = new TreeSet<String>();
		for (InterproResult ir : prot.getInterproResults()) {
			vectorSpaceModel.add(ir.getId());
		}
		for (String blastDb : prot.getBlastResults().keySet()) {
			for (BlastResult br : prot.getBlastResults().get(blastDb)) {
				if (getBlastResultAccessionsToInterproIds().containsKey(
						br.getAccession())) {
					vectorSpaceModel.addAll(blastResultAccessionsToInterproIds
							.get(br.getAccession()));
				}
			}

		}
		return vectorSpaceModel;
	}

	public DomainScoreCalculator(Protein protein) {
		super();
		setProtein(protein);
	}

	/**
	 * After initialization of BlastResults Interpro-Annotations and having
	 * selected all BlastResults to be description candidates, now the
	 * DomainSimilarityScores can be computed.
	 * <ul>
	 * <li>Generate the vector space model (VSM)</li>
	 * <li>For the query protein itself and each BlastResult (Description
	 * Candidate) compute its Domain Weigths Vector</li>
	 * <li>Finally for each BlastResult compute its DomainSimilarityScore as
	 * sim(query-protein, blast-result)</li>
	 * <li>Trigger computation of cumulative token domain similarity scores and
	 * total token domain similarity score in the query protein's
	 * TokenScoreCalculator</li>
	 * <li>Trigger memorization off the maximum found domain similarity score</li>
	 * </ul>
	 * 
	 * @param Protein
	 *            prot
	 */
	public void computeDomainSimilarityScores() {
		setVectorSpaceModel(constructVectorSpaceModel(getProtein()));
		constructDomainWeightVectors(getProtein());
		for (String blastDb : getProtein().getBlastResults().keySet()) {
			for (BlastResult br : getProtein().getBlastResults().get(blastDb)) {
				br.setDomainSimilarityScore(domainWeightSimilarity(getProtein()
						.getDomainWeights(), br.getDomainWeights()));
				getProtein().getTokenScoreCalculator()
						.measureCumulativeDomainSimilarityScores(br);
				getProtein().getTokenScoreCalculator()
						.measureTotalDomainSimilarityScore(br);
				getProtein().getDescriptionScoreCalculator()
						.measureMaxDomainSimilarityScore(br);
			}
		}
	}

	public Protein getProtein() {
		return protein;
	}

	public void setProtein(Protein protein) {
		this.protein = protein;
	}

	public SortedSet<String> getVectorSpaceModel() {
		return vectorSpaceModel;
	}

	public void setVectorSpaceModel(SortedSet<String> vectorSpaceModel) {
		this.vectorSpaceModel = vectorSpaceModel;
	}

	public Map<String, Double> getCumulativeTokenDomainSimilarityScores() {
		return cumulativeTokenDomainSimilarityScores;
	}

	public void setCumulativeTokenDomainSimilarityScores(
			Map<String, Double> cumulativeTokenDomainSimilarityScores) {
		this.cumulativeTokenDomainSimilarityScores = cumulativeTokenDomainSimilarityScores;
	}

	public Double getTotalTokenDomainSimilarityScore() {
		return totalTokenDomainSimilarityScore;
	}

	public void setTotalTokenDomainSimilarityScore(
			Double totalTokenDomainSimilarityScore) {
		this.totalTokenDomainSimilarityScore = totalTokenDomainSimilarityScore;
	}

	public static Map<String, Set<String>> getBlastResultAccessionsToInterproIds() {
		return blastResultAccessionsToInterproIds;
	}

	public static void setBlastResultAccessionsToInterproIds(
			Map<String, Set<String>> blastResultAccessionsToInterproIds) {
		DomainScoreCalculator.blastResultAccessionsToInterproIds = blastResultAccessionsToInterproIds;
	}

	public static Map<String, Set<String>> getBlastResultAccessionsToPfamIds() {
		return blastResultAccessionsToPfamIds;
	}

	public static void setBlastResultAccessionsToPfamIds(
			Map<String, Set<String>> blastResultAccessionsToPfamIds) {
		DomainScoreCalculator.blastResultAccessionsToPfamIds = blastResultAccessionsToPfamIds;
	}
}
