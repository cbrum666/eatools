package no.eatools.diagramgen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import no.bouvet.ohs.args4j.CliApp;
import no.bouvet.ohs.args4j.HelpProducer;
import no.bouvet.ohs.args4j.UsageHelper;
import no.bouvet.ohs.futil.ResourceFinder;
import no.bouvet.ohs.jops.PropertyMap;
import no.eatools.util.EaApplicationProperties;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static no.eatools.util.EaApplicationProperties.EA_DIAGRAM_TO_GENERATE;
import static no.eatools.util.EaApplicationProperties.EA_PROJECT;
import static org.apache.commons.lang.StringUtils.isNotBlank;

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

    @Option(name = "-e", usage = "create file with attribute entries", metaVar = "package to generate elements from")
    private String elementCreationPackage = "";

    @Option(name = "-p", usage = "Property override [property]=[new value],... ", metaVar = "list of key, value pairs")
    private PropertyMap<EaApplicationProperties> propertyMap = EaApplicationProperties.getThePropertyMap();

    @Option(name = "-c", usage = "set connectors", metaVar = "Connector Type")
    private Integer connectorType = null;

//    @Argument(metaVar = PROPERTY_FILE, usage = "property file. If omitted standard file is looked for ", index = 0, required = true)
//    private String propertyFilename;

    @Argument(metaVar = "diagram", usage = "diagram name or number. If omitted, all diagrams are generated", index = 1, required = false)
    private String diagram;

    private final UsageHelper usageHelper = new UsageHelper(this);
    private EaRepo eaRepo;

    public static void main(final String[] args) {
        new EaDiagramGenerator().initMain(args);
    }

    protected void doMain(final String[] args) {
        ResourceFinder.findResourceAsStringList(VERSION_FILE).forEach(LOG::info);

        usageHelper.parse(args);
//        if (LOG.isDebugEnabled()) {
//            final String property = System.getProperty("java.library.path");
//            final StringTokenizer parser = new StringTokenizer(property, ";");
//            while (parser.hasMoreTokens()) {
//                System.err.println(parser.nextToken());
//            }
//        }
        LOG.debug(propertyMap.toString());

//        if(! EA_PROJECT.exists()) {
//            LOG.error("Missing property {}", EA_PROJECT.keyAsPropertyName());
//            usageHelper.ter();
//        }
//        try {
        LOG.info("Using properties" + listAllProperties());

        final File modelFile = new File(EA_PROJECT.value());
        eaRepo = new EaRepo(modelFile);
        if (!eaRepo.open()) {
            usageHelper.terminateWithHelp(-2, ERROR_ON_EXIT);
        }

        if (connectorType != null) {
            adjustConnectors();
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
        if (!EA_DIAGRAM_TO_GENERATE.exists() || isNotBlank(diagram)) {
            generateSpecificDiagram();
        } else {
            // generate all diagrams
            final int count = EaDiagram.generateAll(eaRepo);
            LOG.info("Generated " + count + " diagrams");
        }
        eaRepo.close();
//        } catch (final Exception e) {
//            e.printStackTrace();
//            LOG.error(e.toString());
//            final String msg = ERROR_ON_EXIT;
//            System.out.println(msg);
//        } catch (final Throwable t) {
//            System.err.println("An error occurred " + t);
//        } finally {
//            if (eaRepo != null) {
//                eaRepo.close();
//            }
//        }
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

//    Function create = () -> {
//        return this::createElementFile;
//    }

    private void generateSpecificDiagram() {
        final String diagramName;
        if (isNotBlank(diagram)) {
            diagramName = diagram;
        } else {
            diagramName = EA_DIAGRAM_TO_GENERATE.value();
        }
        final EaDiagram diagram = EaDiagram.findEaDiagram(eaRepo, diagramName);
        if (diagram != null) {
            diagram.writeImageToFile(urlForFileOnly);
        } else {
            LOG.info("diagram '" + diagramName + "' not found");
        }
    }
}
