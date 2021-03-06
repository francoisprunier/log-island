/**
 * Copyright (C) 2016 Hurence (bailet.thomas@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.hurence.logisland.botsearch;

import java.beans.Transient;
import java.io.Serializable;
import java.util.*;
import java.util.regex.Pattern;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.ml.clustering.Clusterable;
import org.apache.commons.math3.ml.distance.EuclideanDistance;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 *
 * fftFlows : In this step, we sample our trace like a binary signal by
 * assigning it to be 1 at each connection start, and 0 in-between connections.
 *
 * @author tom
 */

public class Trace implements Clusterable, Serializable {

	private static final Logger logger = LoggerFactory.getLogger(Trace.class);

	private String ipSource;
	private String ipTarget;
	private double avgUploadedBytes;
	private double avgDownloadedBytes;
	private double avgTimeBetweenTwoFLows;
	private double mostSignificantFrequency;
	private double distanceToNearestCentroid;
	private String centroidName;
	private Set<String> tags = new HashSet<>();

	//private double avgFLowDuration;
	private double smallestTimeInterval;
	private double biggestTimeInterval;
	private double[] durations;

	private final List<HttpFlow> flows = new ArrayList<>();

	@Override
	public String toString() {
		return "Trace{" +
				"ipSource='" + ipSource + '\'' +
				", ipTarget='" + ipTarget + '\'' +
				", avgUploadedBytes=" + avgUploadedBytes +
				", avgDownloadedBytes=" + avgDownloadedBytes +
				", avgTimeBetweenTwoFLows=" + avgTimeBetweenTwoFLows +
				", mostSignificantFrequency=" + mostSignificantFrequency +
				", distanceToNearestCentroid=" + distanceToNearestCentroid +
				", centroidName='" + centroidName + '\'' +
				", smallestTimeInterval=" + smallestTimeInterval +
				", biggestTimeInterval=" + biggestTimeInterval +
				'}';
	}

	/**
	 * take a tab separated string representing a trace and converts it to a
	 * Trace object 10.113.140.213	77.67.21.141	(248.98, 41528.56, 381.64,
	 * 34.91)
	 *
	 * @param line
	 * @return
	 */
	public static Trace parse(String line) throws IllegalArgumentException {

		final Pattern tabPattern = Pattern.compile("\t");
		final Pattern commaPattern = Pattern.compile(",");

		String[] fields = tabPattern.split(line);
		Trace trace = new Trace();
		trace.setIpSource(fields[0]);
		trace.setIpTarget(fields[1]);

		String vector = fields[2].replace("(", "").replace(")", "");
		fields = commaPattern.split(vector);

		if (fields.length == 4) {
			trace.setAvgUploadedBytes(Double.parseDouble(fields[0]));
			trace.setAvgDownloadedBytes(Double.parseDouble(fields[1]));
			trace.setAvgTimeBetweenTwoFLows(Double.parseDouble(fields[2]));
			trace.setMostSignificantFrequency(Double.parseDouble(fields[3]));

			//	trace.setId(Integer.toString(trace.hashCode()));
		} else {
			throw new IllegalArgumentException("unable to parse Trace from String : " + line);
		}

		return trace;
	}

	/**
	 *
	 * <strong>note</strong> computeStats shall be called first
	 *
	 */
	public void compute() throws IllegalArgumentException {

		computeFlowStats();

		double[] samples = sampleFlows();
		double[] magnitudes = computePowerSpectralDensity(samples);
		setMostSignificantFrequency(StatUtils.max(magnitudes));

		// check for a NaN (occuring when all flows occurs at the same time)
		Double maxFreq = getMostSignificantFrequency();
		if (maxFreq.isNaN()) {
			setMostSignificantFrequency(0.0);
		}
	}

	/**
	 * Loop around flows to compute the average time interval between two flows
	 * the average uploaded byte amount as well as downloaded byte amount
	 */
	void computeFlowStats() throws IllegalArgumentException {

		// init some local variables
		int flowsCount = getFlows().size();

		if (flowsCount < 2) {
			throw new IllegalArgumentException("not enough flows to compute a trace : " + flowsCount);
		}

		durations = new double[flowsCount - 1];
		double[] uploads = new double[flowsCount];
		double[] downloads = new double[flowsCount];

		// loop around all flows
		for (int i = 0; i < flowsCount; i++) {
			HttpFlow currentFlow = getFlows().get(i);

			// compute n-1 durations
			if (i != flowsCount - 1) {
				double t0 = currentFlow.getDate().getTime();
				double t1 = getFlows().get(i + 1).getDate().getTime();
				durations[i] = (t1 - t0);
			}
			uploads[i] = currentFlow.getRequestSize();
			downloads[i] = currentFlow.getResponseSize();

			// compute tags (nothing to do with clustering but ...)
			getTags().addAll(currentFlow.getTags());
		}

		// compute stats
		smallestTimeInterval = StatUtils.min(durations);
		biggestTimeInterval = getFlows().get(flowsCount - 1).getDate().getTime() - getFlows().get(0).getDate().getTime();
		setAvgTimeBetweenTwoFLows(StatUtils.mean(durations));
		setAvgUploadedBytes(StatUtils.mean(uploads));
		setAvgDownloadedBytes(StatUtils.mean(downloads));
	}

	/**
	 * We represents our trace like a binary signal by assigning it to be 1 at
	 * each connection start, and 0 in-between connections. To calculate a
	 * high-quality FFT, we                           used a sampling interval
	 * of 1=4th of the smallest time interval in the trace, which ensures that
	 * we do not undersample. However, if the distance between two ﬂows is
	 * extremely small and large gaps occur between other ﬂows of the trace,
	 * this sampling method can lead to a Significant amount of data points. In
	 * such cases, we limit the length of our FFT trace to 2^16 = 65 536
	 * datapoints and accept minor undersampling. We chose this value as the FFT
	 * is fastest for a length of power of two
	 *
	 */
	double[] sampleFlows() {

		//-------------------------------------------------------
		// start with best fit sample unit
		double deltaTime = smallestTimeInterval / 4.0;
		int sampleSize = (int) (biggestTimeInterval / deltaTime);

		// accept some undersampling to limit sample count
		int nearestPowerOf2
			= sampleSize == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(sampleSize - 1);
		if (nearestPowerOf2 > 16) {
			nearestPowerOf2 = 16;
		}

		// FFT works better with power of 2
		sampleSize = (int) Math.pow(2, nearestPowerOf2);
		deltaTime = biggestTimeInterval / sampleSize;
		double[] samples = new double[sampleSize];

		//-------------------------------------------------------
		// set 1 at each flow start, 0 elsewhere
		double durationSum = 0.0;
		for (int i = 0; i < durations.length; i++) {
			durationSum += durations[i];
			int index = (int) (durationSum / deltaTime);

			// watch out out of bounds
			if (index >= sampleSize) {
				index = sampleSize - 1;
			}
			if (index >= 0 && index < samples.length) {
				samples[index] = 1.0;
			}

		}
		return samples;
	}

	/**
	 *
	 * In the next step, we compute the Power Spectral Density (PSD) of the Fast
	 * Fourier Transformation over our sampled trace and extract the most
	 * significant frequency. The FFT peaks are corralated with time
	 * periodicities and resistant against irregular large gaps in the trace. We
	 * observed the introduction of gaps in the wild for bots in which
	 * communication with the C&C server is periodic and then pauses for a
	 * while. When malware authors randomly vary the C&C connection frequency
	 * within a certain window, the random variation lowers the FFT peak.
	 * However, the peak remains detectable and at the same frequency, enabling
	 * the detection of the malware communication.
	 *
	 */
	double[] computePowerSpectralDensity(double[] samples) {

		// compute FFT
		FastFourierTransformer fft = new FastFourierTransformer(DftNormalization.STANDARD);
		Complex[] frequencies = fft.transform(samples, TransformType.FORWARD);

		// take the highest magnitude of power spectral density
		double[] magnitudes = new double[frequencies.length / 2];
		for (int i = 0; i < magnitudes.length; i++) {
			// Convert to db
			magnitudes[i] = 10 * Math.log10(frequencies[i].abs());
		}

		// apply a low pass filter to smooth high frequency magnitudes
		smoothArray(magnitudes, 2.0);

		return magnitudes;
	}

	// values:    an array of numbers that will be modified in place
	// smoothing: the strength of the smoothing filter; 1=no change, larger values smoothes more
	void smoothArray(double[] values, double smoothing) {
		if (values == null || values.length == 0) {
			logger.debug("we won't smooth an empty array, sorry :)");
			return;
		}

		double value = values[0]; // start with the first input
		for (int i = 1; i < values.length; i++) {
			double currentValue = values[i];
			value += (currentValue - value) / smoothing;
			values[i] = value;
		}
	}

	@Override
	@Transient
	public double[] getPoint() {
		double[] vector = {getAvgUploadedBytes(),
				getAvgDownloadedBytes(),
				getAvgTimeBetweenTwoFLows(),
				getMostSignificantFrequency(),};

		return vector;

	}

	public void add(HttpFlow flow) {
		getTags().addAll(flow.getTags());
		getFlows().add(flow);
	}

	/**
	 * Given a list of cluster centroids. Compute the distance to each of them
	 * and choose the nearest one.
	 *
	 * @param klusters
	 */
	public void assignToNearestCentroid(List<TraceCluster> klusters) {

		EuclideanDistance distance = new EuclideanDistance();
		String clusterId = "";
		double minDistance = 0.0;
		for (int i = 0; i < klusters.size(); i++) {

			double d = distance.compute(getPoint(), klusters.get(i).getCenter());
			if (i == 0 || d < minDistance) {
				clusterId = klusters.get(i).getId();
				minDistance = d;
			}
		}

		this.setCentroidName(clusterId);
		this.setDistanceToNearestCentroid(minDistance);
	}

	public String getIpSource() {
		return ipSource;
	}

	public void setIpSource(String ipSource) {
		this.ipSource = ipSource;
	}

	public String getIpTarget() {
		return ipTarget;
	}

	public void setIpTarget(String ipTarget) {
		this.ipTarget = ipTarget;
	}

	public double getAvgUploadedBytes() {
		return avgUploadedBytes;
	}

	public void setAvgUploadedBytes(double avgUploadedBytes) {
		this.avgUploadedBytes = avgUploadedBytes;
	}

	public double getAvgDownloadedBytes() {
		return avgDownloadedBytes;
	}

	public void setAvgDownloadedBytes(double avgDownloadedBytes) {
		this.avgDownloadedBytes = avgDownloadedBytes;
	}

	public double getAvgTimeBetweenTwoFLows() {
		return avgTimeBetweenTwoFLows;
	}

	public void setAvgTimeBetweenTwoFLows(double avgTimeBetweenTwoFLows) {
		this.avgTimeBetweenTwoFLows = avgTimeBetweenTwoFLows;
	}

	public double getMostSignificantFrequency() {
		return mostSignificantFrequency;
	}

	public void setMostSignificantFrequency(double mostSignificantFrequency) {
		this.mostSignificantFrequency = mostSignificantFrequency;
	}

	public double getDistanceToNearestCentroid() {
		return distanceToNearestCentroid;
	}

	public void setDistanceToNearestCentroid(double distanceToNearestCentroid) {
		this.distanceToNearestCentroid = distanceToNearestCentroid;
	}

	public String getCentroidName() {
		return centroidName;
	}

	public void setCentroidName(String centroidName) {
		this.centroidName = centroidName;
	}

	public Set<String> getTags() {
		return tags;
	}

	public void setTags(Set<String> tags) {
		this.tags = tags;
	}

	public List<HttpFlow> getFlows() {
		return flows;
	}
}
