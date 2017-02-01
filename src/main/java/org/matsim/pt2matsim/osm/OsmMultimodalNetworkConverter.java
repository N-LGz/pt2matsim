/*
 * *********************************************************************** *
 * project: org.matsim.*                                                   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2014 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** *
 */

package org.matsim.pt2matsim.osm;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.NetworkCleaner;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.utils.collections.CollectionUtils;
import org.matsim.core.utils.collections.MapUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt2matsim.config.OsmConverterConfigGroup;
import org.matsim.pt2matsim.osm.lib.Osm;
import org.matsim.pt2matsim.osm.lib.OsmData;
import org.matsim.pt2matsim.osm.parser.TagFilter;
import org.matsim.pt2matsim.tools.NetworkTools;

import java.io.File;
import java.util.*;

/**
 * Implementation of a network converter. Modified version from {@link org.matsim.core.utils.io.OsmNetworkReader}
 * Uses a config file ({@link OsmConverterConfigGroup}) to store conversion parameters and default
 * values.
 *
 * @author polettif
 */
public class OsmMultimodalNetworkConverter {

	private final static Logger log = Logger.getLogger(OsmMultimodalNetworkConverter.class);

	protected OsmConverterConfigGroup config;
	private Map<String, OsmConverterConfigGroup.OsmWayParams> highwayParams = new HashMap<>();
	private Map<String, OsmConverterConfigGroup.OsmWayParams> railwayParams = new HashMap<>();

	private OsmData osmData;

	private Network network;

	/**
	 *  Maps for nodes, ways and relations
	 */
	private final Map<Long, Long> wayIds = new HashMap<>();
	private Map<Long, Set<Long>> relationMembers = new HashMap<>();



	/**
	 *  Maps for unknown entities
	 */
	private final Set<String> unknownHighways = new HashSet<>();
	private final Set<String> unknownRailways = new HashSet<>();
	private final Set<String> unknownPTs = new HashSet<>();
	private final Set<String> unknownWays = new HashSet<>();
	private final Set<String> unknownMaxspeedTags = new HashSet<>();
	private final Set<String> unknownLanesTags = new HashSet<>();
	private long id = 0;


	public OsmMultimodalNetworkConverter(OsmData osmData) {
		this.osmData = osmData;
	}

	/**
	 * Converts the osm data according to the parameters defined in config.
	 */
	public void convert(OsmConverterConfigGroup config) {
		this.config = config;
		CoordinateTransformation transformation = TransformationFactory.getCoordinateTransformation("WGS84", config.getOutputCoordinateSystem());
		readWayParams();
		convertToNetwork(transformation);
		cleanRoadNetwork();
	}

	/**
	 * reads the params from the config to different containers.
	 */
	private void readWayParams() {
		for(ConfigGroup e : config.getParameterSets(OsmConverterConfigGroup.OsmWayParams.SET_NAME)) {
			OsmConverterConfigGroup.OsmWayParams w = (OsmConverterConfigGroup.OsmWayParams) e;
			if(w.getOsmKey().equals(Osm.Key.HIGHWAY)) {
				highwayParams.put(w.getOsmValue(), w);
			} else if(w.getOsmKey().equals(Osm.Key.RAILWAY)) {
				railwayParams.put(w.getOsmValue(), w);
			}
		}
	}

	/**
	 * Converts the parsed osm data to MATSim nodes and links.
	 * @param transformation
	 */
	private void convertToNetwork(CoordinateTransformation transformation) {

		if(transformation == null) {
			transformation = TransformationFactory.getCoordinateTransformation("WGS84", "WGS84");
		}

		this.network = NetworkTools.createNetwork();

		Map<Long, Osm.Node> nodes = osmData.getNodes();
		Map<Long, Osm.Way> ways = osmData.getWays();
		Map<Long, Osm.Relation> relations = osmData.getRelations();

		// store of which relation a way is part of
		for(Osm.Relation relation : osmData.getRelations().values()) {
			for(Osm.RelationMember member : relation.members) {
				MapUtils.getSet(member.refId, relationMembers).add(relation.id);
			}
		}

		TagFilter serviceRailTracksFilter = new TagFilter(Osm.Tag.WAY);
		serviceRailTracksFilter.add(Osm.Key.SERVICE);

		// remove unusable ways
		for(Osm.Way way : ways.values()) {
			if(!highwayParams.containsKey(way.tags.get(Osm.Key.HIGHWAY)) && !railwayParams.containsKey(way.tags.get(Osm.Key.RAILWAY)) && !relationMembers.containsKey(way.id)) {
				way.used = false;
			} else if(!nodes.containsKey(way.nodes.get(0)) || !nodes.containsKey(way.nodes.get(way.nodes.size() - 1))) {
				way.used = false;
			}
		}

		// remove unused ways
		Iterator<Map.Entry<Long, Osm.Way>> it = ways.entrySet().iterator();
		while(it.hasNext()) {
			Map.Entry<Long, Osm.Way> entry = it.next();
			if(!entry.getValue().used) {
				it.remove();
			}
		}

		// check which nodes are used
		for(Osm.Way way : ways.values()) {
			if(nodes.containsKey(way.nodes.get(0)) && nodes.containsKey(way.nodes.get(way.nodes.size() - 1))) {
				// first and last are counted twice, so they are kept in all cases
				nodes.get(way.nodes.get(0)).ways++;
				nodes.get(way.nodes.get(way.nodes.size() - 1)).ways++;
			}

			for(Long nodeId : way.nodes) {
				Osm.Node node = nodes.get(nodeId);
				node.used = true;
				node.ways++;
			}
		}

		// Clean network:
		if(!config.getKeepPaths()) {
			// marked nodes as unused where only one way leads through
			// but only if this doesn't lead to links longer than MAX_LINKLENGTH
			for(Osm.Way way : ways.values()) {

				double length = 0.0;
				Osm.Node lastNode = nodes.get(way.nodes.get(0));
				for(int i = 1; i < way.nodes.size(); i++) {
					Osm.Node node = nodes.get(way.nodes.get(i));
					if(node.ways > 1) {
						length = 0.0;
						lastNode = node;
					} else if(node.ways == 1) {
						length += CoordUtils.calcEuclideanDistance(lastNode.coord, node.coord);
						if(length <= config.getMaxLinkLength()) {
							node.used = false;
							lastNode = node;
						} else {
							length = 0.0;
							lastNode = node;
						}
					} else {
						log.warn("Way node with less than 1 ways found.");
					}
				}
			}
			// verify we did not mark nodes as unused that build a loop
			for(Osm.Way way : ways.values()) {
				int prevRealNodeIndex = 0;
				Osm.Node prevRealNode = nodes.get(way.nodes.get(prevRealNodeIndex));

				for(int i = 1; i < way.nodes.size(); i++) {
					Osm.Node node = nodes.get(way.nodes.get(i));
					if(node.used) {
						if(prevRealNode == node) {
						/* We detected a loop between two "real" nodes.
						 * Set some nodes between the start/end-loop-node to "used" again.
						 * But don't set all of them to "used", as we still want to do some network-thinning.
						 * I decided to use sqrt(.)-many nodes in between...
						 */
							double increment = Math.sqrt(i - prevRealNodeIndex);
							double nextNodeToKeep = prevRealNodeIndex + increment;
							for(double j = nextNodeToKeep; j < i; j += increment) {
								int index = (int) Math.floor(j);
								Osm.Node intermediaryNode = nodes.get(way.nodes.get(index));
								intermediaryNode.used = true;
							}
						}
						prevRealNodeIndex = i;
						prevRealNode = node;
					}
				}
			}
		}

		// create the required nodes
		for(Osm.Node node : nodes.values()) {
			if(node.used) {
				org.matsim.api.core.v01.network.Node nn = this.network.getFactory().createNode(Id.create(node.id, org.matsim.api.core.v01.network.Node.class), transformation.transform(node.coord));
				this.network.addNode(nn);
			}
		}

		// create the links
		this.id = 1;
		for(Osm.Way way : ways.values()) {
			Osm.Node fromNode = nodes.get(way.nodes.get(0));
			double length = 0.0;
			Osm.Node lastToNode = fromNode;
			if(fromNode.used) {
				for(int i = 1, n = way.nodes.size(); i < n; i++) {
					Osm.Node toNode = nodes.get(way.nodes.get(i));
					if(toNode != lastToNode) {
						length += CoordUtils.calcEuclideanDistance(lastToNode.coord, toNode.coord);
						if(toNode.used) {
							createLink(this.network, way, fromNode, toNode, length);
							fromNode = toNode;
							length = 0.0;
						}
						lastToNode = toNode;
					}
				}
			}
		}

		// free up memory
		nodes.clear();
		ways.clear();
		relations.clear();

		log.info("= conversion statistics: ==========================");
		log.info("MATSim: # nodes created: " + this.network.getNodes().size());
		log.info("MATSim: # links created: " + this.network.getLinks().size());

		if(this.unknownHighways.size() > 0) {
			log.info("The following highway-types had no defaults set and were thus NOT converted:");
			for(String highwayType : this.unknownHighways) {
				log.info("- \"" + highwayType + "\"");
			}
		}
		if(this.unknownRailways.size() > 0) {
			log.info("The following railway-types had no defaults set and were thus NOT converted:");
			for(String railwayType : this.unknownRailways) {
				log.info("- \"" + railwayType + "\"");
			}
		}
		if(this.unknownPTs.size() > 0) {
			log.info("The following PT-types had no defaults set and were thus NOT converted:");
			for(String ptType : this.unknownPTs) {
				log.info("- \"" + ptType + "\"");
			}
		}
		if(this.unknownWays.size() > 0) {
			log.info("The way-types with the following tags had no defaults set and were thus NOT converted:");
			for(String wayType : this.unknownWays) {
				log.info("- \"" + wayType + "\"");
			}
		}
		log.info("= end of conversion statistics ====================");
	}

	/**
	 * Creates a MATSim link from osm data
	 */
	private void createLink(final Network network, final Osm.Way way, final Osm.Node fromNode, final Osm.Node toNode, final double length) {
		double nofLanes;
		double laneCapacity;
		double freespeed;
		double freespeedFactor;
		boolean oneway;
		boolean onewayReverse = false;
		boolean busOnlyLink = false;

		// load defaults
		String highway = way.tags.get(Osm.Key.HIGHWAY);
		String railway = way.tags.get(Osm.Key.RAILWAY);
		OsmConverterConfigGroup.OsmWayParams wayValues;
		if(highway != null) {
			wayValues = this.highwayParams.get(highway);
			if(wayValues == null) {
				// check if bus route is on link
				if(way.tags.containsKey(Osm.Key.PSV)) {
					busOnlyLink = true;
					wayValues = highwayParams.get(Osm.OsmValue.UNCLASSIFIED);
				} else {
					this.unknownHighways.add(highway);
					return;
				}
			}
		} else if(railway != null) {
			wayValues = this.railwayParams.get(railway);
			if(wayValues == null) {
				this.unknownRailways.add(railway);
				return;
			}
		} else {
			this.unknownWays.add(way.tags.values().toString());
			return;
		}
		nofLanes = wayValues.getLanes();
		laneCapacity = wayValues.getLaneCapacity();
		freespeed = wayValues.getFreespeed();
		freespeedFactor = wayValues.getFreespeedFactor();
		oneway = wayValues.getOneway();

		// check if there are tags that overwrite defaults
		// - check tag "junction"
		if("roundabout".equals(way.tags.get(Osm.Key.JUNCTION))) {
			// if "junction" is not set in tags, get() returns null and equals() evaluates to false
			oneway = true;
		}
		// - check tag "oneway"
		String onewayTag = way.tags.get(Osm.Key.ONEWAY);
		if(onewayTag != null) {
			if("yes".equals(onewayTag)) {
				oneway = true;
			} else if("true".equals(onewayTag)) {
				oneway = true;
			} else if("1".equals(onewayTag)) {
				oneway = true;
			} else if("-1".equals(onewayTag)) {
				onewayReverse = true;
				oneway = false;
			} else if("no".equals(onewayTag)) {
				oneway = false; // may be used to overwrite defaults
			}
		}
		// - check tag "oneway" with trunks, primary and secondary roads
		// 		(if they are marked as such, the default number of lanes should be two instead of one)
		if(highway != null) {
			if(highway.equalsIgnoreCase("trunk") || highway.equalsIgnoreCase("primary") || highway.equalsIgnoreCase("secondary")) {
				if(oneway && nofLanes == 1.0) {
					nofLanes = 2.0;
				}
			}
		}
		// - check tag "maxspeed"
		String maxspeedTag = way.tags.get(Osm.Key.MAXSPEED);
		if(maxspeedTag != null) {
			try {
				freespeed = Double.parseDouble(maxspeedTag) / 3.6; // convert km/h to m/s
			} catch (NumberFormatException e) {
				boolean message = true;
				if(config.getGuessFreeSpeed()) {
					try {
						message = false;
						freespeed = Double.parseDouble(maxspeedTag.substring(0, 2)) / 3.6;
					} catch (NumberFormatException e1) {
						message = true;
					}
				}
				if(!this.unknownMaxspeedTags.contains(maxspeedTag) && message) {
					this.unknownMaxspeedTags.add(maxspeedTag);
					log.warn("Could not parse maxspeed tag: " + e.getMessage() + " (way " + way.id + ") Ignoring it.");
				}
			}
		}
		// - check tag "lanes"
		String lanesTag = way.tags.get(Osm.Key.LANES);
		if(lanesTag != null) {
			try {
				double tmp = Double.parseDouble(lanesTag);
				if(tmp > 0) {
					nofLanes = tmp;
				}
			} catch (Exception e) {
				if(!this.unknownLanesTags.contains(lanesTag)) {
					this.unknownLanesTags.add(lanesTag);
					log.warn("Could not parse lanes tag: " + e.getMessage() + ". Ignoring it.");
				}
			}
		}

		// define the links' capacity and freespeed
		double capacity = nofLanes * laneCapacity;
		if(config.getScaleMaxSpeed()) {
			freespeed = freespeed * freespeedFactor;
		}

		// define modes allowed on link(s)
		//	basic type:
		Set<String> modes = new HashSet<>();
		if(!busOnlyLink && highway != null) {
			modes.add(TransportMode.car);
		}
		if(busOnlyLink) {
			modes.add("bus");
			modes.add(TransportMode.pt);
		}

		if(railway != null && railwayParams.containsKey(railway)) {
			modes.add(railway);
		}

		if(modes.isEmpty()) {
			modes.add("unknownStreetType");
		}

		//	public transport: get relation which this way is part of, then get the relations route=* (-> the mode)
		Set<Long> containingRelations = relationMembers.get(way.id);
		if(containingRelations != null) {
			for(Long containingRelationId : containingRelations) {
				Osm.Relation rel = osmData.getRelations().get(containingRelationId);
				String mode = rel.tags.get(Osm.Key.ROUTE);
				if(mode != null) {
					if(mode.equals(Osm.OsmValue.TROLLEYBUS)) {
						mode = Osm.OsmValue.BUS;
					}
					modes.add(mode);
				}
			}
		}

		// only create link, if both nodes were found, node could be null, since nodes outside a layer were dropped
		Id<org.matsim.api.core.v01.network.Node> fromId = Id.create(fromNode.id, org.matsim.api.core.v01.network.Node.class);
		Id<org.matsim.api.core.v01.network.Node> toId = Id.create(toNode.id, org.matsim.api.core.v01.network.Node.class);
		if(network.getNodes().get(fromId) != null && network.getNodes().get(toId) != null) {
			String origId = Long.toString(way.id);

			if(!onewayReverse) {
				Link l = network.getFactory().createLink(Id.create(this.id, Link.class), network.getNodes().get(fromId), network.getNodes().get(toId));
				l.setLength(length);
				l.setFreespeed(freespeed);
				l.setCapacity(capacity);
				l.setNumberOfLanes(nofLanes);
				l.setAllowedModes(modes);
				NetworkUtils.setOrigId(l, origId);

				network.addLink(l);
				this.id++;
			}
			if(!oneway) {
				Link l = network.getFactory().createLink(Id.create(this.id, Link.class), network.getNodes().get(toId), network.getNodes().get(fromId));
				l.setLength(length);
				l.setFreespeed(freespeed);
				l.setCapacity(capacity);
				l.setNumberOfLanes(nofLanes);
				l.setAllowedModes(modes);
				NetworkUtils.setOrigId(l, origId);

				network.addLink(l);
				this.id++;
			}
		}
	}

	/**
	 * Runs the network cleaner on the street network.
	 */
	private void cleanRoadNetwork() {
		String tmpFilename = "tmpNetwork"+osmData+".xml.gz";
		Set<String> roadModes = CollectionUtils.stringToSet("car,bus");
		Network roadNetwork = NetworkTools.createFilteredNetworkByLinkMode(network, roadModes);
		Network restNetwork = NetworkTools.createFilteredNetworkExceptLinkMode(network, roadModes);

		new NetworkCleaner().run(roadNetwork);
		new NetworkWriter(roadNetwork).write(tmpFilename);
		Network roadNetworkReadAgain = NetworkTools.readNetwork(tmpFilename);
		if(!new File(tmpFilename).delete()) { log.info("Could not delete temporary road network file"); }
		new NetworkCleaner().run(roadNetworkReadAgain);
		NetworkTools.integrateNetwork(roadNetworkReadAgain, restNetwork);
		this.network = roadNetworkReadAgain;
	}


	/**
	 * @return the network
	 */
	public Network getNetwork() {
		return this.network;
	}

}

