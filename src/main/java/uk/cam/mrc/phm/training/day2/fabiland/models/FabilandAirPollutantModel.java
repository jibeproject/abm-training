package uk.cam.mrc.phm.training.day2.fabiland.models;

import cern.colt.map.tfloat.OpenIntFloatHashMap;
import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.data.Zone;
import de.tum.bgu.msm.health.airPollutant.dispersion.EmissionSpatialDispersion;
import de.tum.bgu.msm.health.airPollutant.dispersion.Grid;
import de.tum.bgu.msm.health.airPollutant.emission.CreateVehicles;
import de.tum.bgu.msm.health.data.DataContainerHealth;
import de.tum.bgu.msm.health.data.LinkInfo;
import de.tum.bgu.msm.health.io.LinkInfoWriter;
import de.tum.bgu.msm.models.AbstractModel;
import de.tum.bgu.msm.models.ModelUpdateListener;
import de.tum.bgu.msm.properties.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.emissions.EmissionModule;
import org.matsim.contrib.emissions.Pollutant;
import org.matsim.contrib.emissions.utils.EmissionsConfigGroup;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.events.EventsManagerImpl;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.Tuple;

import java.util.*;

import static org.matsim.core.controler.Injector.createInjector;

public class FabilandAirPollutantModel extends AbstractModel implements ModelUpdateListener {
    public static final int EMISSION_TIME_BIN_SIZE = 3600;
    private static final double EMISSION_GRID_SIZE = 20;
    private static final double EMISSION_SMOOTH_RADIUS = 100;
    private int latestMatsimYear = -1;
    private static final Logger logger = LogManager.getLogger(FabilandAirPollutantModel.class);
    private Scenario scenario;
    private final Config initialMatsimConfig;
    private final Set<Pollutant> pollutantSet = new HashSet<>();

    public FabilandAirPollutantModel(DataContainer dataContainer, Properties properties, Random random, Config config) {
        super(dataContainer, properties, random);
        this.initialMatsimConfig = config;
        Pollutant[] pollutants = new Pollutant[]{Pollutant.NO2,Pollutant.PM2_5, Pollutant.PM2_5_non_exhaust};
        for(Pollutant pollutant : pollutants){
            this.pollutantSet.add(pollutant);
        }
        ((DataContainerHealth)dataContainer).setPollutantSet(pollutantSet);
    }

    @Override
    public void setup() {
        Scenario scenario = ScenarioUtils.loadScenario(initialMatsimConfig);
        Map<Id<Link>, LinkInfo> linkInfoMap = new HashMap<>();
        for(Link link : scenario.getNetwork().getLinks().values()){
            linkInfoMap.put(link.getId(), new LinkInfo(link.getId()));
        }
        ((DataContainerHealth)dataContainer).setLinkInfo(linkInfoMap);
        logger.info("Initialized Link Info for " +  ((DataContainerHealth)dataContainer).getLinkInfo().size() + " links ");

        for(Zone zone : dataContainer.getGeoData().getZones().values()){
            Map<Pollutant, OpenIntFloatHashMap> pollutantMap = new HashMap<>();
            ((DataContainerHealth)dataContainer).getZoneExposure2Pollutant2TimeBin().put(zone, pollutantMap);
        }
    }

    @Override
    public void prepareYear(int year) {
    }

    @Override
    public void endYear(int year) {
        logger.warn("Air pollutant exposure model end year:" + year);
        if(properties.main.startYear == year || properties.healthData.exposureModelYears.contains(year)) {
            //car emission
            latestMatsimYear = year;
            String outputDirectoryRoot = properties.main.baseDirectory + "scenOutput/"
                    + properties.main.scenarioName + "/matsim/" + latestMatsimYear;
            Config config = ConfigUtils.loadConfig(initialMatsimConfig.getContext());

            scenario = ScenarioUtils.createMutableScenario(config);
            scenario.getConfig().controller().setOutputDirectory(outputDirectoryRoot);
            prepareConfig();
            String eventFileWithoutEmissions = scenario.getConfig().controller().getOutputDirectory()  + "/" + year + ".output_events.xml.gz";
            String eventFileWithEmissions = scenario.getConfig().controller().getOutputDirectory()  + "/" + year + ".output_events_emission.xml.gz";
            String vehicleFile = scenario.getConfig().controller().getOutputDirectory() + "/"  + year + ".output_vehicles.xml.gz";
            String vehicleFileWithEmissionType = scenario.getConfig().controller().getOutputDirectory() + "/" + year + ".vehicles_emission.xml.gz";
            CreateVehicles createVehicles = new CreateVehicles(scenario);
            createVehicles.runVehicleType();
            createVehicles.runVehicle(vehicleFile, vehicleFileWithEmissionType);
            updateConfig(vehicleFileWithEmissionType);
            scenario = ScenarioUtils.loadScenario(scenario.getConfig());
            createEmissionEventsOffline(eventFileWithoutEmissions,eventFileWithEmissions);

            //car traffic concentration
            latestMatsimYear = year;
            outputDirectoryRoot = properties.main.baseDirectory + "scenOutput/"
                    + properties.main.scenarioName + "/matsim/" + latestMatsimYear;
            config = ConfigUtils.loadConfig(initialMatsimConfig.getContext());
            config.controller().setOutputDirectory(outputDirectoryRoot);

            scenario = ScenarioUtils.loadScenario(config);
            runEmissionGridAnalyzer(year, eventFileWithEmissions, 1.);

            String outputDirectory = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName + "/";
            new LinkInfoWriter().writeData((DataContainerHealth) dataContainer, outputDirectory, Day.thursday, "car");
        }
    }


    @Override
    public void endSimulation() {

    }
    
    private void createEmissionEventsOffline(String eventsFileWithoutEmissions, String eventsFileWithEmission) {
        logger.warn("Start create Emission events offline...");
        EventsManager eventsManager = new EventsManagerImpl();

        AbstractModule module = new AbstractModule(){
            @Override
            public void install(){
                bind( Scenario.class ).toInstance( scenario );
                bind( EventsManager.class ).toInstance( eventsManager );
                bind( EmissionModule.class ) ;
            }
        };


        com.google.inject.Injector injector = createInjector(scenario.getConfig(), module);

        EmissionModule emissionModule = injector.getInstance(EmissionModule.class);

        EventWriterXML emissionEventWriter = new EventWriterXML(eventsFileWithEmission);
        emissionModule.getEmissionEventsManager().addHandler(emissionEventWriter);

        MatsimEventsReader matsimEventsReader = new MatsimEventsReader(eventsManager);
        matsimEventsReader.readFile(eventsFileWithoutEmissions);

        emissionEventWriter.closeFile();
        logger.warn("Created Emission events file.");
    }

    public Config prepareConfig() {
        if(scenario.getConfig().getModules().containsKey("emissions")){
            scenario.getConfig().removeModule("emissions");
        }
        scenario.getConfig().travelTimeCalculator().setTraveltimeBinSize(3600);
        EmissionsConfigGroup emissionsConfig= new EmissionsConfigGroup();
        emissionsConfig.setDetailedVsAverageLookupBehavior(EmissionsConfigGroup.DetailedVsAverageLookupBehavior.directlyTryAverageTable);
        emissionsConfig.setAverageColdEmissionFactorsFile(Properties.get().main.baseDirectory+ Properties.get().healthData.COLD_EMISSION_FILE);
        emissionsConfig.setAverageWarmEmissionFactorsFile(Properties.get().main.baseDirectory+ Properties.get().healthData.HOT_EMISSION_FILE);
        emissionsConfig.setNonScenarioVehicles(EmissionsConfigGroup.NonScenarioVehicles.ignore);
        emissionsConfig.setHbefaVehicleDescriptionSource(EmissionsConfigGroup.HbefaVehicleDescriptionSource.fromVehicleTypeDescription);
        scenario.getConfig().addModule(emissionsConfig);
        return scenario.getConfig();
    }

    public Config updateConfig(String vehicleFile) {
        String populationFile = scenario.getConfig().controller().getOutputDirectory() + "/" + latestMatsimYear + ".output_plans.xml.gz";

        scenario.getConfig().vehicles().setVehiclesFile(vehicleFile);
        scenario.getConfig().plans().setInputFile(populationFile);

        return scenario.getConfig();
    }


    private void runEmissionGridAnalyzer(int year, String eventsFileWithEmission, double scalingFactor) {
        logger.info("Creating grid cell air pollutant exposure for year " + year );

        logger.info("Apply scale factor: " + scalingFactor + " to emission grid analyzer.");

        EmissionSpatialDispersion gridAnalyzer =	new	EmissionSpatialDispersion.Builder()
                .withNetwork(scenario.getNetwork())
                .withTimeBinSize(EMISSION_TIME_BIN_SIZE)
                .withGridSize(EMISSION_GRID_SIZE)
                .withSmoothingRadius(EMISSION_SMOOTH_RADIUS)
                .withCountScaleFactor(1./scalingFactor)
                .withGridType(EmissionSpatialDispersion.GridType.Square)
                .build();

        gridAnalyzer.processTimeBinsWithEmissions(eventsFileWithEmission);

        List<Coordinate> coordinates = new ArrayList<>();
        for(Zone zone : dataContainer.getGeoData().getZones().values()){
            Coordinate centroid = ((Geometry) (zone.getZoneFeature().getDefaultGeometry())).getCentroid().getCoordinate();
            coordinates.add(centroid);
        }

        while(gridAnalyzer.hasNextTimeBin()){
            assembleLinkZoneConcentration(year, gridAnalyzer.processNextTimeBin(coordinates));
        }

    }

    private void assembleLinkZoneConcentration(int year, Tuple<Double, Grid<Map<Pollutant, Float>>>  gridEmissionMap) {
        logger.info("Updating link air pollutant exposure for year: " + year + "| time of day: " + gridEmissionMap.getFirst() + ".");
        int startTime = (int) Math.floor(gridEmissionMap.getFirst());

        Grid<Map<Pollutant,	Float>> grid =	gridEmissionMap.getSecond();

        for(Link link : scenario.getNetwork().getLinks().values()){
            Map<Pollutant, OpenIntFloatHashMap> exposure2Pollutant2TimeBin =  ((DataContainerHealth)dataContainer).getLinkInfo().get(link.getId()).getExposure2Pollutant2TimeBin();
            Grid.Cell<Map<Pollutant,Float>> toNodeCell = grid.getCell(new Coordinate(link.getToNode().getCoord().getX(),link.getToNode().getCoord().getY()));
            Grid.Cell<Map<Pollutant,Float>> fromNodeCell = grid.getCell(new Coordinate(link.getFromNode().getCoord().getX(),link.getFromNode().getCoord().getY()));
            Grid.Cell<Map<Pollutant,Float>> middleNodeCell = grid.getCell(new Coordinate(link.getCoord().getX(),link.getCoord().getY()));
            for(Pollutant pollutant : pollutantSet){
                //TODO: use avg of link from node, to node and middle point as link pollutant concentration?
                double toNodeCellExposure = 0;
                double fromNodeCellExposure = 0;
                double middleNodeCellExposure = 0;
                if(toNodeCell!=null){
                    if(toNodeCell.getValue().containsKey(pollutant)) {
                        toNodeCellExposure = toNodeCell.getValue().get(pollutant);
                    }
                }
                if(fromNodeCell!=null){
                    if(fromNodeCell.getValue().containsKey(pollutant)){
                        fromNodeCellExposure = fromNodeCell.getValue().get(pollutant);
                    }
                }
                if(middleNodeCell!=null){
                    if(middleNodeCell.getValue().containsKey(pollutant)){
                        middleNodeCellExposure = middleNodeCell.getValue().get(pollutant);
                    }
                }
                double avg = (toNodeCellExposure + fromNodeCellExposure + middleNodeCellExposure)/3;
                if(exposure2Pollutant2TimeBin.get(pollutant)==null){
                    OpenIntFloatHashMap exposureByTimeBin = new OpenIntFloatHashMap();
                    exposureByTimeBin.put(startTime, (float) avg);
                    exposure2Pollutant2TimeBin.put(pollutant, exposureByTimeBin);
                }else {
                    exposure2Pollutant2TimeBin.get(pollutant).put(startTime, (float) avg);
                }
            }
            ((DataContainerHealth)dataContainer).getLinkInfo().get(link.getId()).setExposure2Pollutant2TimeBin(exposure2Pollutant2TimeBin);
        }

        for(Zone zone :dataContainer.getGeoData().getZones().values()){
            Map<Pollutant, OpenIntFloatHashMap> exposure2Pollutant2TimeBin =  ((DataContainerHealth)dataContainer).getZoneExposure2Pollutant2TimeBin().getOrDefault(zone, new HashMap<>());

            Coordinate coordinate = ((Geometry) zone.getZoneFeature().getDefaultGeometry())
                    .getCentroid().getCoordinate();

            Grid.Cell<Map<Pollutant,Float>> zoneCell = grid.getCell(coordinate);

            for(Pollutant pollutant : pollutantSet){
                if(exposure2Pollutant2TimeBin.get(pollutant)==null){
                    OpenIntFloatHashMap exposureByTimeBin = new OpenIntFloatHashMap();
                    exposureByTimeBin.put(startTime, zoneCell.getValue().getOrDefault(pollutant, 0.f));
                    exposure2Pollutant2TimeBin.put(pollutant, exposureByTimeBin);
                }else {
                    exposure2Pollutant2TimeBin.get(pollutant).put(startTime, zoneCell.getValue().getOrDefault(pollutant,0.f));
                }
            }

            ((DataContainerHealth)dataContainer).getZoneExposure2Pollutant2TimeBin().put(zone, exposure2Pollutant2TimeBin);
        }

        logger.info("Updating air pollutant exposure for year: " + year + "| time of day: " + gridEmissionMap.getFirst() + "finished.");

    }
}
