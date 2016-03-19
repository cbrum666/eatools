package no.eatools.diagramgen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import no.bouvet.ohs.args4j.CliApp;
import no.bouvet.ohs.args4j.HelpProducer;
import no.bouvet.ohs.args4j.UsageHelper;
import no.bouvet.ohs.futil.ResourceFinder;
import no.bouvet.ohs.jops.EnumProperty;
import no.bouvet.ohs.jops.PropertyMap;
import no.eatools.util.EaApplicationProperties;

import org.apache.commons.io.FilenameUtils;
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

    @Option(name = "-r", usage = "create relationships of package", metaVar = "package")
    private String pack = "";

    @Option(name = "-n", usage = "create url for result file only")
    private boolean urlForFileOnly = false;

    @Option(name = "-np", usage = "create url from nodePath ", metaVar = "Node Path as exported from EA")
    private String nodePath = "";

    @Option(name = "-e", usage = "create file with attribute entries", metaVar = "package to generate elements from")
    private String elementCreationPackage = "";

    @Option(name = "-p", usage = "Property override [property]=[new value],... ", metaVar = "list of key, value pairs")
    private PropertyMap<EaApplicationProperties> propertyMap = getThePropertyMap();

    @Option(name = "-c", usage = "Set connectors on given diagram to type", metaVar = "Connector Type")
    private Integer connectorType = null;

    @Option(name = "-v", usage = "Show version and exit")
    private boolean showVersion = false;

    @Option(name = "-l", usage = "List properties of elements")
    private boolean list = false;

    @Option(name = "-m", usage = "Create HTML report to path", metaVar = "Path to html reports")
    private String htmlOutputPath = null;

    @Option(name = "-tv", usage = "Add tagged values to all elements and attributes in given package", metaVar = "Comma separated list of tagged " +
            "values to add")
    private List<String> taggedValues = new ArrayList<>();

    @Option(name = "-cl", usage = "List all components recursively in given package", metaVar = "Package root")
    private String packageForList = "";

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
            if(repos != null) {
                repos.close();
            }
        }
    }

    public EaDiagramGenerator() {
        super(true);
    }

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
                .sorted((o1, o2) -> o1.name()
                                      .compareTo(o2.name()))
                .forEach(e -> System.out.println(e.getKeyValue()));

        if (showVersion) {
            ResourceFinder.findResourceAsStringList(VERSION_FILE)
                          .forEach(System.out::println);
            return;
        }
        LOG.info("Using properties" + listAllProperties());
        if (isNotBlank(nodePath)) {
            final String urlForNode = nodePathToUrl(nodePath);
            EaDiagram.updateDiagramUrlFile(urlForNode);
            return;
        }
        startProgress();

        final String reposString = EA_PROJECT.value();
        LOG.info("Trying repos {}", reposString);
        final String normalizedFileName = FilenameUtils.normalize(reposString);
        final File modelFile = new File(normalizedFileName);
        LOG.info("Trying repos: asProperty: [{}] to file: [{}]", reposString, modelFile.getAbsolutePath());
        eaRepo = new EaRepo(modelFile);
        if (!eaRepo.open()) {
            usageHelper.terminateWithHelp(-2, ERROR_ON_EXIT);
        }

        executeTasks();
        eaRepo.close();
        stopProgress();
        LOG.info("Finished");
    }

    private void executeTasks() {
        if (list) {
            if (isBlank(pack)) {
                usageHelper.terminateWithHelp(-2, "No package to list elements in");
            }
            final EaPackage eaPackage = new EaPackage(pack, eaRepo);
            eaPackage.listElementProperties();
            return;
        }

        if (isNotBlank(packageForList)) {
            System.out.println("Listing components in package: [" + packageForList + "]");
            final EaPackage eaPackage = new EaPackage(packageForList, eaRepo);
            eaPackage.listElements(EaMetaType.COMPONENT);
            eaPackage.listElements(EaMetaType.INTERFACE);
            return;
        }

        if (connectorType != null) {
            adjustConnectors();
        }

        if (!taggedValues.isEmpty()) {
            if (EA_TOP_LEVEL_PACKAGE.exists()) {
                final EaPackage eaPackage = new EaPackage(EA_TOP_LEVEL_PACKAGE.value(), eaRepo);
                eaPackage.setTaggedValues(taggedValues);
                return;
            } else {
                usageHelper.terminateWithHelp(-2, EA_TOP_LEVEL_PACKAGE.getMessage());
            }
        }

        if (isNotBlank(pack)) {
            final EaPackage eaPackage = new EaPackage(pack, eaRepo);
            eaPackage.generatePackageRelationships();
            return;
        }
        if (isNotBlank(elementCreationPackage)) {
            createElementFile();
            return;
        }
        if (htmlOutputPath != null) {
            eaRepo.generateHtml(htmlOutputPath);
            return;
        }
        if (isNotBlank(diagram)) {
            generateSpecificDiagram();
        } else {
            // generate all diagrams
            final int count = eaRepo.generateAllDiagramsFromRoot();
            final String msg = String.format("Generated %d diagrams", count);
            LOG.info(msg);
            System.out.println(msg);
        }
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

    private void adjustConnectors() {
        final EaDiagram eaDiagram = EaDiagram.findEaDiagram(eaRepo, diagram);
        if (eaDiagram != null) {
            eaDiagram.setAllConnectorsToStyle(0);
        }
    }

    private void createElementFile() {
        final EaPackage eaPackage = new EaPackage(elementCreationPackage, eaRepo);
        eaPackage.generateAttributesFile();
    }

    private void generateSpecificDiagram() {
        final EaDiagram eaDiagram = EaDiagram.findEaDiagram(eaRepo, diagram);
        if (eaDiagram != null) {
            eaDiagram.writeImageToFile(urlForFileOnly);
        } else {
            LOG.info("diagram '{}' not found", diagram);
        }
    }

//    private void generateReposHtml() {
//        org.sparx.Repository r = new org.sparx.Repository();
//
//        System.out.println("Repository: " + args[0]);
//        System.out.println("Package:    " + args[1]);
//        System.out.println("Output:     " + args[2]);
//        r.OpenFile(args[0]);
//        r.GetProjectInterface().RunHTMLReport(args[1], args[2], "PNG", "<default>", ".html");
//        r.CloseFile();
//    }
}
