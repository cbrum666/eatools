package no.eatools.diagramgen;

import java.io.File;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import no.bouvet.ohs.args4j.CliApp;
import no.bouvet.ohs.args4j.HelpProducer;
import no.bouvet.ohs.args4j.UsageHelper;
import no.bouvet.ohs.futil.ResourceFinder;
import no.bouvet.ohs.jops.EnumProperty;
import no.bouvet.ohs.jops.Enums;
import no.bouvet.ohs.jops.PropertyMap;
import no.eatools.util.EaApplicationProperties;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static no.eatools.util.EaApplicationProperties.*;
import static no.eatools.util.NameNormalizer.*;
import static org.apache.commons.lang.StringUtils.*;

/**
 * Utility to be used from the command line to output all diagrams in an EA repo
 * with logical filenames, i.e. the name used in the model, instead of the arbitrary
 * name generated by EA when using its 'HTML Report' function.
 *
 * @author Per Spilling (per.spilling@objectware.no)
 * @FindBy(css="")
 */
public class EaDiagramGenerator extends CliApp implements HelpProducer {
    public static final String VERSION_FILE = "version.txt";
    private static final transient Logger LOG = LoggerFactory.getLogger(EaDiagramGenerator.class);
    public static final String ERROR_ON_EXIT = "An error occurred. This might be caused by an incorrect diagramgen-repo connect string.\n" +
            "Verify that the connect string in the ea.application.properties file is the same as\n" +
            "the connect string that you can find in Enterprise Architect via the File->Open Project dialog";

    @Option(name = "-r", usage = "Create relationships of package(s)", metaVar = "package")
    private String pack = "";

    @Option(name = "-n", usage = "Create url for result file only")
    private boolean urlForFileOnly = false;

    @Option(name = "-np", usage = "Create url from nodePath ", metaVar = "Node Path as exported from EA")
    private String nodePath = "";

    @Option(name = "-e", usage = "Create file with attribute entries", metaVar = "package(s) to generate elements from")
    private String elementCreationPackage = "";

    @Option(name = "-b", usage = "Create baseline for packages in the -e option", metaVar = "<versionNo>, {<Notes>}")
    private String baseline = "";

    @Option(name = "-p", usage = "Property override [property]=[new value],... ", metaVar = "list of key, value pairs")
    private PropertyMap<EaApplicationProperties> propertyMap = getThePropertyMap();

    @Option(name = "-c", usage = "Set connectors on given diagram to type", metaVar = "Connector Type")
    private String connectorType = null;

    @Option(name = "-v", usage = "Show version and exit")
    private boolean showVersion = false;

    @Option(name = "-l", usage = "List properties of elements")
    private boolean list = false;

    @Option(name = "-m", usage = "Create HTML report to path", metaVar = "Path to html reports")
    private String htmlOutputPath = null;

    @Option(name = "-tv", usage = "Add tagged values to all elements and attributes in given package", metaVar = "Comma separated list of tagged " +
            "values to add")
    private List<String> taggedValues = new ArrayList<>();

    @Option(name = "-cl", usage = "List all components recursively in given package(s)", metaVar = "Package root")
    private String packageForList = "";

    @Option(name = "-pl", usage = "List all package with hierarchical names", metaVar = "Package root")
    private String rootForPackageList = "";

    @Option(name = "-ad", usage = "Auto generate diagrams for elements in given package(s) recursively", metaVar = "Package root")
    private String packageForAutoDiagrams = "";

    @Option(name = "-i", usage = "Import elements from Json file", metaVar = "Json file")
    private String importJsonFile = "";

    @Option(name = "-o", usage = "Overwrite operations if match on name only")
    private boolean overwriteOps = false;

    //    @Argument(metaVar = PROPERTY_FILE, usage = "property file. If omitted standard file is looked for ", index = 0, required = true)
//    private String propertyFilename;

    @Argument(metaVar = "diagram", usage = "diagram name or number. If omitted, all diagrams are generated", index = 1, required = false)
    private String diagram;

    private final UsageHelper usageHelper = new UsageHelper(this);
    private EaRepo eaRepo;

    public static void main(final String[] args) {

        final EaDiagramGenerator eaDiagramGenerator = new EaDiagramGenerator();
        try {
            eaDiagramGenerator.initMain(args);
        } catch (final Throwable e) {
            LOG.error("\nTerminated with error: ", e);
            System.out.println("\nTerminated with error: " + e);
            e.printStackTrace();
        } finally {
            eaDiagramGenerator.stopProgress();
            final EaRepo repos = eaDiagramGenerator.eaRepo;
            if (repos != null) {
                repos.close();
            }
        }
    }

    public EaDiagramGenerator() {
        super(true);
    }

    @Override
    protected void doMain(final String[] args) {
        setDiagram();
        ResourceFinder.findResourceAsStringList(VERSION_FILE)
                      .forEach(e -> {
                          LOG.info(e);
                          System.out.println(e);
                      });
        usageHelper.parse(args);
        LOG.debug(propertyMap.toString());
        LOG.info(taggedValues.toString());

        getThePropertyMap()
                .keySet()
                .stream()
                .filter(EnumProperty::exists)
                .sorted(Comparator.comparing(Enum::name))
                .forEach(e -> System.out.println(e.getKeyValue()));

        if (showVersion) {
            ResourceFinder.findResourceAsStringList(VERSION_FILE)
                          .forEach(System.out::println);
            return;
        }
        final List<String> allProperties = Arrays.asList(listAllProperties().toString()
                                                                            .split(","));
        allProperties.sort(String::compareTo);

        LOG.info("Using properties" + allProperties.toString()
                                                   .replaceAll(",", "\n"));
        final String reposString = EA_PROJECT.value();
        LOG.info("Trying repos [{}]", reposString);
        final String normalizedFileName = cygPathToWindowsPath(reposString);
        final File modelFile = new File(normalizedFileName);
        LOG.info("Trying repos: asProperty: [{}] to file: [{}]", reposString, modelFile.getAbsolutePath());
        eaRepo = new EaRepo(modelFile);
        if (!eaRepo.open()) {
            usageHelper.terminateWithHelp(-2, ERROR_ON_EXIT);
        }

        if (isNotBlank(nodePath)) {
            final EaDiagram eaDiagram = eaRepo.findDiagramByGUID(nodePath);
            String urlForNode = nodePathToUrl(nodePath);
            urlForNode = eaDiagram.writeImageToFile(true);
            EaDiagram.updateDiagramUrlFile(urlForNode);
            return;
        }
        startProgress();


        executeTasks();
        eaRepo.close();
        stopProgress();
        LOG.info("Finished");
    }

    /**
     * Command switch
     */
    private void executeTasks() {
        if (list) {
            if (isBlank(pack)) {
                usageHelper.terminateWithHelp(-2, "No package to list elements in");
            }
            executeOnPackages(pack, false, EaPackage::listElementProperties, "Listing Element Properties in [{}]");
            return;
        }
        if(isNotBlank(importJsonFile)) {
            new ElementImporter(eaRepo, importJsonFile).importComponents(overwriteOps);
            return;
        }
        if (isNotBlank(rootForPackageList)) {
            listAllPackages(rootForPackageList);
            return;
        }
        if (isNotBlank(packageForList)) {
            listElements(packageForList);
            return;
        }
        if (isNotBlank(connectorType)) {
            adjustConnectors(connectorType);
        }
        if (!taggedValues.isEmpty()) {
            setTaggedValues(taggedValues);
            return;
        }
        if (isNotBlank(pack)) {
            executeOnPackages(pack, false, EaPackage::generatePackageRelationships, "Generating relationships for [{}]");
            return;
        }
        if (isNotBlank(baseline)) {
            createBaselines(elementCreationPackage, baseline);
            return;
        }
        if (isNotBlank(elementCreationPackage)) {
            executeOnPackages(elementCreationPackage, true, EaPackage::generateDDEntryFile, "Creating DD entry file for [{}]");
            return;
        }
        if (isNotBlank(packageForAutoDiagrams)) {
            if(isNotBlank(diagram)) {
                final List<EaElement> elements = eaRepo.findElementsInPackage(packageForAutoDiagrams, diagram);
                for (final EaElement element : elements) {
                    final EaDiagram eaDiagram = eaRepo.createOrUpdateStandardDiagram(element);
                    if (eaDiagram != null) {
                        LOG.info("Created/updated [{}] status [{}]", eaDiagram.getName(), eaDiagram.getStatus());
                    }
                }
            } else {
                executeOnPackages(packageForAutoDiagrams, true, EaPackage::generateAutoDiagramsRecursively, "Creating AUTO diagrams for package [{}]");
            }
            System.out.println("--------> Number of diagrams created/updated :" + eaRepo.getNoOfDiagramsCreated());
            return;
        }
        if (isNotBlank(htmlOutputPath)) {
            eaRepo.generateHtml(htmlOutputPath);
            return;
        }
        if (isNotBlank(diagram)) {
            generateSpecificDiagram(diagram);
        } else if (EA_ROOTPKG.exists()) {
            // generate all diagrams
            logFinalReport(eaRepo.generateAllDiagramsFromRoot());
        } else {
            logFinalReport(eaRepo.generateAllDiagramsFromAllRoots());
        }
    }

    private void logFinalReport(final int count) {
        final String msg = String.format("Generated %d diagrams", count);
        LOG.info(msg);
        System.out.println(msg);
    }

    private void listElements(final String packages) {
        for (final String aPackage : toListOfPackages(packages)) {
            System.out.println("Listing components in package: [" + aPackage + "]");
            final EaPackage eaPackage = new EaPackage(aPackage, eaRepo);
            eaPackage.listElements(EaMetaType.COMPONENT);
            eaPackage.listElements(EaMetaType.INTERFACE);
        }
    }

    private void setTaggedValues(final List<String> taggedValues) {
        if (EA_TOP_LEVEL_PACKAGE.exists()) {
            final EaPackage eaPackage = new EaPackage(EA_TOP_LEVEL_PACKAGE.value(), eaRepo);
            eaPackage.setTaggedValues(taggedValues);
        } else {
            usageHelper.terminateWithHelp(-2, EA_TOP_LEVEL_PACKAGE.getMessage());
        }
    }

    private void createBaselines(final String packages, final String baseline) {
        if (isBlank(packages)) {
            LOG.error("No baseline packages are specified");
            return;
        }
        final String[] baselineElms = baseline.split(",");
        final String notes = ZonedDateTime.now()
                                          .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + (baselineElms.length > 1 ? baselineElms[1] : EMPTY);
        createBaselines(baselineElms[0], notes, packages);
    }

    private String possiblyFromFile(final String elementCreationPackage) {
        if(isBlank(elementCreationPackage)) {
            return elementCreationPackage;
        }
        final File packagesFile = new File(elementCreationPackage);
        if (packagesFile.canRead()) {
            try {
                return FileUtils.readLines(packagesFile)
                                .stream()
                                .map(org.apache.commons.lang.StringUtils::trimToEmpty)
                                .filter(l -> !l.startsWith("#"))
                                .collect(Collectors.joining(","));
            } catch (final IOException e) {
                final String errormsg = "Error reading file " + packagesFile.getAbsolutePath() + " reason: " + e.getMessage();
                LOG.error(errormsg);
                System.out.println(errormsg);
                return elementCreationPackage;
            }
        } else {
            LOG.info("[{}] is not a readable file, using it as is", packagesFile.getAbsoluteFile());
        }
        return elementCreationPackage;
    }

    private void setDiagram() {
        if (isBlank(diagram)) {
            diagram = EA_DIAGRAM_TO_GENERATE.value();
        }
    }

    @Override
    public List<PropertyMap> getPropertyMaps() {
        final List<PropertyMap> result = new ArrayList<>();
        result.add(propertyMap);
        return result;
    }

    private void adjustConnectors(final String connectorType) {
        if (!Enums.hasValueFor(EaConnectorStyle.class, connectorType)) {
            usageHelper.terminateWithHelp(-2, "Unknown connector type [" + connectorType + "]");
        }
        final EaDiagram eaDiagram = EaDiagram.findEaDiagram(eaRepo, diagram);
        if (eaDiagram != null) {
            eaDiagram.setAllConnectorsToStyle(Enums.fromString(EaConnectorStyle.class, connectorType, Enums.CaseConversion.TO_UPPER));
        }
    }

    private void listAllPackages(final String rootForPackageList) {
        final EaPackage rootpkg = eaRepo.findInPackageCache(rootForPackageList);
        eaRepo.findAllPackages(rootpkg)
              .forEach(p -> System.out.println(p.getHierarchicalName()));
    }

    private void createBaselines(final String versionNo, final String notes, final String elementCreationPackage) {
//        executeOnPackages(packageForAutoDiagrams, EaPackage::createBaseline(versionNo, notes), "Creating baseline for package [{}], version [{}],
//  Note [{}]", versionNo, notes);
        final List<String> packages = toListOfPackages(elementCreationPackage);
        if(packages.isEmpty()) {
            LOG.warn("Package list is empty [{}]", elementCreationPackage);
        }
        for (final String pack : packages) {
            LOG.info("Creating baseline for package [{}], version [{}],  Note [{}]", pack, versionNo, notes);
            final EaPackage eaPackage = eaRepo.findInPackageCache(pack);
            if (eaPackage != null) {
                eaPackage.createBaseline(versionNo, notes);
            } else {
                LOG.warn("Unable to find packge [{}]. No baseline is created", pack);
            }
        }
    }


    private void executeOnPackages(final String pack, final boolean useCache, final Consumer<EaPackage> function, final String msg, final Object...
            additionalLogParams) {
        toListOfPackages(pack).stream()
                              .map(p -> {
                                  if (useCache) {
                                      return eaRepo.findInPackageCache(p);
                                  } else {
                                      return new EaPackage(p, eaRepo);
                                  }
                              })
                              .filter(Objects::nonNull)
                              .forEach(p -> {
                                           LOG.info(msg, p, additionalLogParams);
                                           function.accept(p);
                                       }
                              );
    }

    private void generateSpecificDiagram(final String diagram) {
        final EaDiagram eaDiagram = EaDiagram.findEaDiagram(eaRepo, diagram);
        if (eaDiagram != null) {
            final String diagramUrl = eaDiagram.writeImageToFile(urlForFileOnly);
            LOG.info("Diagram created {}", defaultIfBlank(diagramUrl, "-- No diagram --"));
        } else {
            LOG.info("diagram '{}' not found", this.diagram);
        }
    }

    List<String> toListOfPackages(final String packageList) {
        return Arrays.stream(StringUtils.trimToEmpty(possiblyFromFile(packageList))
                                        .split(","))
                     .filter(StringUtils::isNotBlank)
                     .map(StringUtils::trimToEmpty)
                     .collect(Collectors.toList());
    }
}
