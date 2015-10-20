package no.eatools.diagramgen;

import java.io.File;
import java.util.Date;
import java.util.StringTokenizer;

import no.bouvet.ohs.args4j.HelpProducer;
import no.bouvet.ohs.args4j.PropertyMap;
import no.bouvet.ohs.args4j.UsageHelper;
import no.bouvet.ohs.futil.ResourceFinder;
import no.eatools.util.EaApplicationProperties;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import static no.eatools.util.EaApplicationProperties.*;
import static org.apache.commons.lang.StringUtils.isNotBlank;

/**
 * Utility to be used from the command line to output all diagrams in an EA repo
 * with logical filenames, i.e. the name used in the model, instead of the arbitrary
 * name generated by EA when using its 'HTML Report' function.
 *
 * @author Per Spilling (per.spilling@objectware.no)
 */
public class EaDiagramGenerator implements HelpProducer {
    public static final String PROPERTY_FILE = "propertyFile";
    public static final String VERSION_FILE = "version.txt";
    static Log log = LogFactory.getLog(EaDiagramGenerator.class);
    private static final String DEFAULT_PROPERTY_FILE = getPropertiesFilename();

    @Option(name = "-h", usage = "show help")
    private boolean help = false;

    @Option(name = "-r", usage = "create relationships of package", metaVar = "package")
    private String pack = "";

    @Option(name = "-n", usage = "create url for result file only")
    private boolean urlForFileOnly = false;

    @Option(name = "-e", usage = "create file with attribute entries")
    private boolean createElementFile = false;

    @Option(name = "-p", usage = "Property override [property]=[new value],... ", metaVar = "list of key, value pairs")
    private PropertyMap propertyMap = new PropertyMap(EaApplicationProperties.class);

    @Argument(metaVar = PROPERTY_FILE, usage = "property file. If omitted standard file is looked for ", index = 0, required = false)
    private String propertyFilename;

    @Argument(metaVar = "diagram", usage = "diagram name or number. If omitted, all diagrams are generated", index = 1, required = false)
    private String diagram;

    private final UsageHelper usageHelper = new UsageHelper(this);

    public static void main(final String[] args) {
        new EaDiagramGenerator().doMain(args);
    }

    private void doMain(final String[] args) {
        for (final String s : ResourceFinder.findResourceAsStringList( VERSION_FILE)) {
            log.info(s);
        }
        usageHelper.parse(args);
        if(log.isDebugEnabled()) {
            final String property = System.getProperty("java.library.path");
            final StringTokenizer parser = new StringTokenizer(property, ";");
            while (parser.hasMoreTokens()) {
                System.err.println(parser.nextToken());
            }
        }
        log.debug(propertyMap);

        EaRepo eaRepo = null;
        try {
            init(propertyFilename, propertyMap);
            log.info("Using properties" + printAllProperties());

            final File modelFile = new File(EA_PROJECT.value());
            eaRepo = new EaRepo(modelFile);
            System.out.println(new Date());
            eaRepo.open();
            System.out.println(new Date());

            if (isNotBlank(pack)) {
                final EaPackage eaPackage = new EaPackage(pack, eaRepo);
                eaPackage.generatePackageRelationships();
                return;
            }
            if(createElementFile) {
                createElementFile(eaRepo);
                return;
            }
            if (!EA_DIAGRAM_TO_GENERATE.exists() || isNotBlank(diagram)) {
                generateSpecificDiagram(eaRepo);
            } else {
                // generate all diagrams
                final int count = EaDiagram.generateAll(eaRepo);
                log.info("Generated " + count + " diagrams");
            }
            eaRepo.close();
        } catch (final Exception e) {
            e.printStackTrace();
            log.error(e);
            final String msg = "An error occurred. This might be caused by an incorrect diagramgen-repo connect string.\n" +
                    "Verify that the connect string in the ea.application.properties file is the same as\n" +
                    "the connect string that you can find in Enterprise Architect via the File->Open Project dialog";
            System.out.println(msg);
        } catch (final Throwable t) {
            System.err.println("An error occurred " + t);
        } finally {
            if (eaRepo != null) {
                eaRepo.close();
            }
        }
    }

    private void createElementFile(EaRepo eaRepo) {
        final EaPackage eaPackage = new EaPackage("MeteringPoint", eaRepo);
        eaPackage.generateAttributesFile();
    }

    private void generateSpecificDiagram(final EaRepo eaRepo) {
        final String diagramName;
        if (isNotBlank(diagram)) {
            diagramName = diagram;
        } else {
            diagramName = EA_DIAGRAM_TO_GENERATE.value();
        }
        final EaDiagram diagram;
        if (StringUtils.isNumeric(diagramName)) {
            final int diagramId = Integer.parseInt(diagramName);
            diagram = EaDiagram.findDiagramById(eaRepo, diagramId);
        } else {
            diagram = EaDiagram.findDiagram(eaRepo, diagramName);
        }
        if (diagram != null) {
            diagram.writeImageToFile(urlForFileOnly);
        } else {
            log.info("diagram '" + diagramName + "' not found");
        }
    }

    @Override
    public boolean isInHelp() {
        return help;
    }
}
