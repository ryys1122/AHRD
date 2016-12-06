package ahrd.controller;

import static ahrd.controller.Settings.getSettings;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

import ahrd.exception.MissingAccessionException;
import ahrd.exception.MissingInterproResultException;
import ahrd.exception.MissingProteinException;
import ahrd.view.GeneticTrainerOutputWriter;
import nu.xom.ParsingException;

public class GeneticTrainer extends Trainer {

	private static final Double GENERATIONAL_SURVIVAL_RATE = 0.2;
	private static final Double GENERATIONAL_OFFSPRING_RATE = 0.2;
	private static final Double GENERATIONAL_MUTANT_RATE = 0.2;

	private static int numberOfSurvivors;
	private static int numberOfOffspring;
	private static int numberOfMutants;
	private Integer generationBestParametersWereFoundIn;
	
	private GeneticTrainerOutputWriter outWriter;

	/**
	 * Constructor initializes the Settings as given in the argument input.yml
	 * 
	 * @param pathToInputYml
	 * @throws IOException
	 */
	public GeneticTrainer(String pathToInputYml) throws IOException {
		super(pathToInputYml);
		this.outWriter = new GeneticTrainerOutputWriter();
		numberOfSurvivors = (int) Math.round(getSettings().getPopulationSize() * GENERATIONAL_SURVIVAL_RATE);
		numberOfOffspring = (int) Math.round(getSettings().getPopulationSize() * GENERATIONAL_OFFSPRING_RATE);
		numberOfMutants = (int) Math.round(getSettings().getPopulationSize() * GENERATIONAL_MUTANT_RATE);
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		System.out.println("Usage:\njava -Xmx2g -cp ahrd.jar ahrd.controller.GeneticTrainer input.yml\n");

		try {
			GeneticTrainer trainer = new GeneticTrainer(args[0]);
			trainer.setup(false); // false -> Don't log memory and time-usages
			if (getSettings().hasGeneOntologyAnnotations() && getSettings().hasReferenceGoAnnotations()) {
				getSettings().setFindHighestPossibleGoScore(true);
			}
			// After the setup the unique short accessions are no longer needed:
			trainer.setUniqueBlastResultShortAccessions(null);
			trainer.setupReferenceDescriptions();
			trainer.setupGoAnnotationEvaluation();
			// Try to find optimal parameters heuristically:
			trainer.train();
			// Calculate the average maximum evaluation score AHRD could have
			// possibly achieved:
			trainer.calcAvgMaxEvaluationScore();

			// Write final output
			Settings bestSettings = getSettings().clone();
			bestSettings.setParameters(trainer.getBestParameters());
			trainer.outWriter.writeFinalOutput(bestSettings, trainer.getAvgMaxEvaluationScore(),
					trainer.getGenerationBestParametersWereFoundIn());
			System.out.println("Logged path through parameter- and score-space into:\n"
					+ getSettings().getPathToSimulatedAnnealingPathLog());
			System.out.println("Written output into:\n" + getSettings().getPathToOutput());
		} catch (Exception e) {
			System.err.println("We are sorry, an unexpected ERROR occurred:");
			e.printStackTrace(System.err);
		}

	}

	/**
	 * Random first generation. Survival of the fittest, recombination of fit
	 * survivors, mutants of fit survivors and random parameter sets for the
	 * rest in each succeeding generation.
	 * 
	 * @throws IOException
	 * @throws MissingInterproResultException
	 * @throws SQLException
	 * @throws MissingAccessionException 
	 * @throws ParsingException 
	 * @throws MissingProteinException 
	 */
	public void train() throws MissingInterproResultException, IOException, SQLException, MissingAccessionException, MissingProteinException, ParsingException {
		Set<Parameters> population = new HashSet<Parameters>();
		// Set up first generation
		List<String> sortedDistinctBlastDatabaseNames = new ArrayList<String>();
		sortedDistinctBlastDatabaseNames.addAll(getSettings().getBlastDatabases());
		Collections.sort(sortedDistinctBlastDatabaseNames);
		// Add parameters from YML-input to first generation (enables seeding with high mean evaluation score parameter set)
		Parameters seed = getSettings().getParameters().clone();
		seed.setOrigin("seed");
		population.add(seed);
		for (int i = 2; i <= getSettings().getPopulationSize(); i++) {
			population.add(Parameters.randomParameters(sortedDistinctBlastDatabaseNames));
		}
		int generation = 1;
		double diffAvgEvalScoreToLastGeneration = 0;
		// simulate generational succession
		while (generation <= getSettings().getNumberOfGenerations()) {
			// Show progress
			System.out.println("Evaluating generation " + generation + " of " + getSettings().getNumberOfGenerations());
			// Determine the fitness of each individual (parameter set) in the
			// population
			for (Parameters individual : population) {
				if (individual.getAvgEvaluationScore() == null) {
					getSettings().setParameters(individual);
					reinitializeBlastResults();
					// Iterate over all Proteins and assign the best scoring Human
					// Readable Description
					assignHumanReadableDescriptions();
					if (getSettings().hasGeneOntologyAnnotations() && getSettings().hasReferenceGoAnnotations()) {
						goAnnotsStringToObject();
					}
					// Evaluate AHRD's performance for each Protein:
					calculateEvaluationScores();
					// Estimate average performance of current Parameters:
					calcAveragesOfEvalScoreTPRandFPR();
//					if(getSettings().getParameters().getOrigin().equals("seed")) {
//						writeProteins(generation);
//					}
//					System.out.println(individual.getOrigin() + ": " + getSettings().getAvgEvaluationScore());
				}
			}

			// Survival of the fittest
			NavigableSet<Parameters> fitnessRanking = new TreeSet<Parameters>();
			fitnessRanking.addAll(population);
			population.clear();
			while (fitnessRanking.size() > numberOfSurvivors) {
				fitnessRanking.pollFirst();
			}
			population.addAll(fitnessRanking);

			// Recombination of fit survivors
			int count = 0;
			while (population.size() < numberOfSurvivors + numberOfOffspring) {
				Parameters mama = getRandomFitIndividual(fitnessRanking);
				Parameters papa = getRandomFitIndividual(fitnessRanking);
				while (papa == mama) {
					papa = getRandomFitIndividual(fitnessRanking);
				}
				population.add(mama.recombine(papa));
				count++;
				// Algorithm has converged i.e. has enriched the set of survivors with parameter sets too similar to each other, to result in new sets via recombination.
				// Recombination is aborted and the places in the population are instead filled with additional mutants.
				if (count > numberOfOffspring * 3)
					break;
			}

			// Mutants of fit survivors
			while (population.size() < numberOfSurvivors + numberOfOffspring + numberOfMutants) {
				population.add(getRandomFitIndividual(fitnessRanking).neighbour(null));
			}

			// Fill the rest of the population with new parameter sets
			while (population.size() <= getSettings().getPopulationSize()) {
				population.add(Parameters.randomParameters(sortedDistinctBlastDatabaseNames));
			}

			// Remember the best parameter set and the generation it was found
			// in
			if (getBestParameters() != null) {
				diffAvgEvalScoreToLastGeneration = fitnessRanking.last().getAvgEvaluationScore() - getBestParameters().getAvgEvaluationScore();
			}
			if (getBestParameters() == null
					|| fitnessRanking.last().getAvgEvaluationScore() > getBestParameters().getAvgEvaluationScore()) {
				setBestParameters(fitnessRanking.last().clone());
				setGenerationBestParametersWereFoundIn(generation);
			}
			// Write output of current iteration:
			this.outWriter.writeGeneticIterationOutput(generation, getBestParameters(), diffAvgEvalScoreToLastGeneration, getBestParameters().getOrigin());
			generation += 1;
		}
	}
	
	/**
	 * Returns a random individual (parameter set) from a navigable set of
	 * parameter sets ordered according to their fitness (evaluation score) A
	 * strong bias towards fitter individuals is applied
	 * 
	 * @param fitnessRanking
	 * @return random fit parameter set
	 */
	private static Parameters getRandomFitIndividual(NavigableSet<Parameters> fitnessRanking) {
		Parameters randomFitIndividual = null;
		int placeInRanking = Integer.MAX_VALUE;
		while (placeInRanking > fitnessRanking.size()) {
			placeInRanking = (int) Math.ceil(Math.abs(Utils.random.nextGaussian() * ((double) fitnessRanking.size() / 3)));
		}
		Iterator<Parameters> decendingRankingIter = fitnessRanking.descendingIterator();
		for (int i = 1; i <= placeInRanking; i++) {
			randomFitIndividual = decendingRankingIter.next();
		}
		return randomFitIndividual;
	}

	public Integer getGenerationBestParametersWereFoundIn() {
		return generationBestParametersWereFoundIn;
	}

	public void setGenerationBestParametersWereFoundIn(Integer generationBestParametersWereFoundIn) {
		this.generationBestParametersWereFoundIn = generationBestParametersWereFoundIn;
	}
	
}
