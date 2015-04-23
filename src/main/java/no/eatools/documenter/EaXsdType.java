package no.eatools.documenter;

import no.eatools.diagramgen.EaRepo;
import no.eatools.util.ObjectGraph;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sparx.Element;

/**
 * Represents an XSDType element as found in an EA Repos.
 *
 * @author ohs
 */
public class EaXsdType {
    private static final transient Log LOG = LogFactory.getLog(EaXsdType.class);

    private final EaRepo eaRepo;

    public EaXsdType(EaRepo eaRepo) {
        this.eaRepo = eaRepo;
    }

    public void setNewDocumentation(String s) {
        Element xsdType = eaRepo.findXsdType(eaRepo.getRootPackage(), "ComplexType1");

        ObjectGraph objectGraph = new ObjectGraph();
        LOG.debug(objectGraph.createDotGraph(xsdType));

        LOG.debug("Old doc " + xsdType.GetNotes());
        LOG.debug("New doc " + s);
        xsdType.SetNotes(s);
        xsdType.Update();
    }
//    public static void setElementDoc(String pkgName, String elementName, String docString) {
//        EaRepo =
//        Element element =
//        return null;
//    }
}