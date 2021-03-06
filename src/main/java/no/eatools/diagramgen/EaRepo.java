package no.eatools.diagramgen;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import no.bouvet.ohs.ea.dd.DDEntry;
import no.bouvet.ohs.ea.dd.DDEntryList;
import no.eatools.util.EaApplicationProperties;
import no.eatools.util.IntCounter;
import no.eatools.util.NameNormalizer;
import no.eatools.util.PackageCache;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sparx.Collection;
import org.sparx.Connector;
import org.sparx.Datatype;
import org.sparx.Diagram;
import org.sparx.DiagramObject;
import org.sparx.Element;
import org.sparx.Package;
import org.sparx.Project;
import org.sparx.Repository;

import static no.eatools.diagramgen.EaMetaType.*;
import static no.eatools.util.EaApplicationProperties.*;
import static org.apache.commons.lang3.StringUtils.*;

/**
 * Utilities for use with the EA (Enterprise Architect DLL).
 * todo reconsider transactional model, i.e. how/when to open/close the Repos.
 * <p/>
 * Note that the terminology in these methods refer to the corresponding UML elements.
 * E.g. getClassesInPackage means "find and return all elements of UML-type Class in the UML Package".
 * <p/>
 * The class assume that no two elements of same UML type (e.g. Class, Component) and same name may exist
 * in the same namespace (Package).
 * <p/>
 * Date: 21.okt.2008
 *
 * @author AB22273 et al.
 */
public class EaRepo {
// ------------------------------ FIELDS ------------------------------

    /* Boolean flags that can be used as input params */
    public static final boolean RECURSIVE = true;
    public static final boolean NON_RECURSIVE = false;
    private static final transient Logger LOG = LoggerFactory.getLogger(EaRepo.class);


    /* Name of the UML stereotype for an XSDschema package */
    private static final String xsdSchemaStereotype = "XSDschema";

    /* The character encoding to use for XSD generation */
    private static final String xmlEncoding = "UTF-8";
    private final Project project;
    private final Pattern elementPattern;
    private final Pattern stereotypeFilter;
    private final File reposFile;
    private Repository repository = null;
    private boolean isOpen = false;
    private String reposString;
    private final Pattern packagePattern;
    //Global repository root
    private EaPackage rootPackage;
    private final Map<String, DDEntry> previousElements = new HashMap<>();
    private final List<EaMetaType> metaTypesThatHasDiagrams = Arrays.asList(COMPONENT, INTERFACE, QUEUE, PROCESS, DATA_STORE, WEB_PAGE, CLIENT_PAGE);
    private final PackageCache packageCache = new PackageCache();
    private int noOfDiagramsCreated;

// --------------------------- CONSTRUCTORS ---------------------------

    /**
     * @param repositoryFile local file or database connection string
     */
    public EaRepo(final File repositoryFile) {
        reposFile = repositoryFile;
        packagePattern = establishFilter(EA_PACKAGE_FILTER);
        elementPattern = establishFilter(EA_ELEMENT_FILTER);
        stereotypeFilter = establishFilter(EA_STEREOTYPE_FILTER);
        establishExcludeMap();
        LOG.info("Types not included in auto-diagrams: {}", EaDiagram.bannedElementTypes);
        ensureRepoIsOpen();
        project = repository.GetProjectInterface();
    }

    private Pattern establishFilter(final EaApplicationProperties filter) {
        final String packagePatternRegexp = filter.value();
        Pattern pattern = null;
        if (StringUtils.isNotBlank(packagePatternRegexp)) {
            pattern = Pattern.compile(packagePatternRegexp);
            LOG.info("Setting {} filter to  [{}] : [{}]", filter, packagePatternRegexp, pattern.pattern());
        }
        return pattern;
    }

    private void establishExcludeMap() {
        final String previousElementsFileName = EA_EXCLUDE_FILE.value();
        if (isNotBlank(previousElementsFileName)) {
            final DDEntryList ddEntries = DDEntryList.parseFromFile(previousElementsFileName);
            for (final DDEntry ddEntry : ddEntries) {
                previousElements.put(ddEntry.getGUID(), ddEntry);
            }
        }
    }

    /**
     * Find the top level (aka root) package in a given repository.
     * todo check for NPEs.
     *
     * @return the root package or possibly null if there are no root package in the repository.
     * This is normally the "Views" package or the "Model" package, but it may have an arbitrary name.
     */
    private EaPackage findRootPackage() {
        ensureRepoIsOpen();
        final String rootPkgName = EA_ROOTPKG.value();
        System.out.println("root package name = " + rootPkgName);
        for (final Package aPackage : repository.GetModels()) {
            if (aPackage.GetName()
                        .equalsIgnoreCase(rootPkgName)) {
                LOG.debug("Found top level (root) package: [{}]", aPackage.GetName());
                return new EaPackage(aPackage, this, null);
            }
        }
        throw new RuntimeException("Root pkg '" + rootPkgName + "' not found");
    }

    /**
     *
     * @return a list of all root packages in all models within the repos.
     */
    private List<EaPackage> findAllRootPackages() {
        final List<EaPackage> result = new ArrayList<>();
        for (final Package aPackage : repository.GetModels()) {
            result.add(new EaPackage(aPackage, this, null));
        }
        return result;
    }

// --------------------- GETTER / SETTER METHODS ---------------------

    /**
     * Lazy init
     *
     * @return
     */
    public EaPackage getRootPackage() {
        if (rootPackage == null) {
            rootPackage = findRootPackage();
        }
        return rootPackage;
    }

// ------------------------ CANONICAL METHODS ------------------------

    @Override
    public String toString() {
        return reposFile.getAbsolutePath() + " " + this.repository.toString();
    }

// -------------------------- OTHER METHODS --------------------------

    private EaDiagram findDiagramByName(final Package pkg, final String diagramName, final boolean recursive) {
        if (pkg == null) {
            return null;
        }
        LOG.info("Looking for diagram [{}] in package [{}]", diagramName, pkg.GetName());
//        for (Element element : pkg.GetElements()) {
//            element.GetAttributes()
//        }pkg.GetElements()

        final EaDiagram diagram = findDiagramInPackage(pkg, diagramName);
        if (diagram != null) {
            return diagram;
        }

        if (recursive) {
//            packageCache.get()
            for (final Package p : pkg.GetPackages()) {
                final EaDiagram d = findDiagramByName(p, diagramName, recursive);
                if (d != null) {
                    return d;
                }
            }
        }
        return null;
    }

    private EaDiagram findDiagramInPackage(final Package pkg, final String diagramName) {
        for (final Diagram diagram : pkg.GetDiagrams()) {
            if (diagram.GetName()
                       .equals(diagramName) || diagramName.equals(Integer.toString(diagram.GetDiagramID()))) {
                LOG.info("Diagram name = [{}] ID: [{}]", diagram.GetName(), diagram.GetDiagramID());
                return new EaDiagram(this, diagram, getPackagePath(pkg));
            }
        }
        return null;
    }

    /**
     * Closes the Enterprise Architect model repository.
     */
    public void close() {
        if (isOpen && repository != null) {
            LOG.info("Closing repository: " + reposString);
            repository.SetFlagUpdate(true);
            repository.CloseFile();
            repository.Exit();
            repository = null;
        }
        isOpen = false;
    }

    public boolean deleteObjectInPackage(final Package pkg, final String objectName, final Element classifier) {
        if (pkg == null) {
            return false;
        }
        short index = 0;
        for (final Element element : pkg.GetElements()) {
            if (element.GetName()
                       .equals(objectName)) {
                if ((classifier == null) || (classifier.GetElementID() == element.GetClassifierID())) {
                    return deleteByIndex(pkg, index);
                }
            }
            ++index;
        }
        return false;
    }

    private boolean deleteByIndex(final Package pkg, final short indexToDelete) {
        if (indexToDelete != -1) {
            pkg.GetElements()
               .Delete(indexToDelete);
            pkg.Update();
            pkg.GetElements()
               .Refresh();
            return true;
        }
        return false;
    }

    public boolean deleteClass(final EaPackage pack, final String componentName) {
        return deleteElement(pack, componentName, CLASS);
    }

    public boolean deleteComponent(final EaPackage pack, final String componentName) {
        return deleteElement(pack, componentName, COMPONENT);
    }

    private boolean deleteElement(final EaPackage pack, final String name, final EaMetaType metaType) {
        if ((pack == null) || (pack.unwrap() == null)) {
            return false;
        }
        final Package pkg = pack.unwrap();
        short index = 0;
        for (final Element element : pkg.GetElements()) {
            if (element.GetName()
                       .equals(name) && element.GetMetaType()
                                               .equals(metaType.toString())) {
                return deleteByIndex(pkg, index);
            }
            ++index;
        }
        return false;
    }

    public boolean deletePackage(final EaPackage pack, final boolean recursive) {
        if ((pack == null) || (pack.unwrap() == null)) {
            return false;
        }
        short index = 0;
        short indexToDelete = -1;
        final org.sparx.Package parent = pack.getParent()
                                             .unwrap();
        for (final Package child : parent.GetPackages()) {
            if (child.GetName()
                     .equals(pack.getName())) {
                indexToDelete = index;
                break;
            }
            ++index;
        }
        if (indexToDelete != -1) {
            parent.GetPackages()
                  .Delete(indexToDelete);
            parent.Update();
            parent.GetPackages()
                  .Refresh();
            return true;
        }
        return false;
    }

    public List<String> findAllMetaTypesInModel() {
        final Set<String> result = new HashSet<>();
        findMetaTypesInPackage(getRootPackage(), result);
        return new ArrayList<>(result);
    }

    //todo use package cache
    private void findMetaTypesInPackage(final EaPackage pkg, final Set<String> result) {
        for (final Element element : pkg.unwrap()
                                        .GetElements()) {
            result.add(element.GetMetaType());
        }
        for (final Package aPackage : pkg.unwrap()
                                         .GetPackages()) {
            findMetaTypesInPackage(new EaPackage(aPackage, this, pkg), result);
        }
    }

    /**
     * Find UML Object elements inside a specific UML Package.
     * Non-recursive, searches the top-level (given) package only.
     *
     * @param pack the Package to search in.
     * @return
     */
    public List<Element> findComponentInstancesInPackage(final EaPackage pack) {
        return findElementsOfTypeInPackage(pack, COMPONENT).stream()
                                                           .filter(e -> COMPONENT.toString()
                                                                                 .equals(e.GetClassifierType()))
                                                           .collect(Collectors.toList());
    }

    public EaDiagram findDiagramByName(final String diagramName) {
        return findDiagramByName(getRootPackage().unwrap(), diagramName, true);
    }

    public EaDiagram findDiagramById(final int diagramId) {
        try {
            final Diagram diagram = repository.GetDiagramByID(diagramId);
            if (diagram == null) {
                return null;
            }
            final String packagePath = getPackagePath(findPackageByID(diagram.GetPackageID()));
            return new EaDiagram(this, diagram, packagePath);
        } catch (final Exception e) {
            LOG.error("Could not find diagram with id [{}] in repos [{}]", diagramId, this);
            return null;
        }
    }

    public EaDiagram findDiagramByGUID(final String diagramGuid) {
        try {
            final Diagram diagram = repository.GetDiagramByGuid(diagramGuid);
            if (diagram == null) {
                return null;
            }
            final String packagePath = getPackagePath(findPackageByID(diagram.GetPackageID()));
            return new EaDiagram(this, diagram, packagePath);
        } catch (final Exception e) {
            LOG.error("Could not find diagram with guid [{}] in repos [{}]", diagramGuid, this);
            return null;
        }
    }

    public Element findElementByID(final int elementId) {
        if (elementId == 0) {
            return null;
        }
        ensureRepoIsOpen();
        return repository.GetElementByID(elementId);
    }

    /**
     * Find an element of a specific EaMetaType with a given name.
     *
     * @param pack
     * @param type
     * @param name
     * @return null if no match is found.
     */
    public Optional<Element> findElementOfType(final EaPackage pack, final EaMetaType type, final String name) {
        ensureRepoIsOpen();
        final String trimmedName = name.trim();

        return findElementsOfTypeInPackage(pack, type).stream()
                                                      .filter(e -> e.GetName()
                                                                    .equals(trimmedName))
                                                      .findFirst();
    }

    /**
     * Find UML Node elements inside a specific UML Package.
     * Non-recursive, searches the top-level (given) package only.
     *
     * @param pack
     * @return
     */
    public List<Element> findNodesInPackage(final EaPackage pack) {
        return findElementsOfTypeInPackage(pack, NODE);
    }

    public Connector findOrCreateAssociation(final Element from, final Element to, final String name) {
        // todo check for existence
//        from.GetConnectors().AddNew(name, )
//        for (Connector c : from.GetConnectors()) {
//            if (c.GetSupplierEnd())
//        }
        return null;
    }

    /**
     * @param definedPackage
     * @param className
     * @return
     */
    public Element findOrCreateClassInPackage(final EaPackage definedPackage, final String className) {
        ensureRepoIsOpen();

        return findNamedElementOnList(findClassesInPackage(definedPackage), className)
                .orElseGet(() -> addElementInPackage(definedPackage, className,
                                                     CLASS, null));
    }

    /**
     * @param pack
     * @param objectName
     * @param classifier
     * @return
     */
    public Element findOrCreateObjectInPackage(final EaPackage pack, final String objectName, final Element classifier) {
        ensureRepoIsOpen();

        // We allow for same name on different elements of different type, therefore we must also check type
        for (final Element element : findObjectsInPackage(pack)) {
            if (element.GetName()
                       .equals(objectName) && isOfType(element, classifier)) {
                return element;
            }
        }
        return addElementInPackage(pack, objectName, OBJECT, classifier);
    }

    private Optional<Element> findNamedElementOnList(final List<Element> elementList, final String elementName) {
        ensureRepoIsOpen();
        return elementList.stream()
                          .filter(e -> e.GetName()
                                        .equals(elementName))
                          .findFirst();
    }

    /**
     * Find UML Class elements inside a specific UML Package.
     * Non-recursive, searches the top-level (given) package only.
     *
     * @param pack the Package to serach in.
     * @return
     */
    public List<Element> findClassesInPackage(final EaPackage pack) {
        return findElementsOfTypeInPackage(pack, CLASS);
    }

    public Element findOrCreateComponentInstanceInPackage(final EaPackage pack, final String name, final Element classifier) {
        final Element component = findOrCreateComponentInPackage(pack, name);
        if (classifier != null) {
            component.SetClassifierID(classifier.GetElementID());
            component.Update();
            component.Refresh();
        }
        return component;
    }

    /**
     * @param definedPackage
     * @param componentName
     * @return
     */
    public Element findOrCreateComponentInPackage(final EaPackage definedPackage, final String componentName) {
        ensureRepoIsOpen();
        return findNamedElementOnList(findComponentsInPackage(definedPackage), componentName)
                .orElseGet(() -> addElementInPackage(definedPackage,
                                                     componentName,
                                                     COMPONENT, null));
    }

    /**
     * Find UML Component elements inside a specified UML Package.
     * Non-recursive, searches the top-level (given) package only.
     *
     * @param pack
     * @return
     */
    public List<Element> findComponentsInPackage(final EaPackage pack) {
        return findElementsOfTypeInPackage(pack, COMPONENT);
    }

    /**
     * Assemble a List of all Model elements of a certain EaMetaType in the given package.
     * Sub-packages are not examined (non-recursive).
     *
     * @param pkg  the Package to look in.
     * @param type the type of Element to look for.
     * @return a List of found Elements, possibly empty, but never null.
     */
    public List<Element> findElementsOfTypeInPackage(final EaPackage pkg, final EaMetaType type) {
        ensureRepoIsOpen();
        return pkg.findElementsOfType(type);
    }

    /**
     * todo move to EaDiagram class...
     *
     * @param pkg  the Package to create the Diagram in.
     * @param name name of the Diagram, if null, the Diagram will have the same name as the Package.
     * @param type the type of UML Diagram to look for or create.
     * @return the Diagram created or found.
     */
    public Diagram findOrCreateDiagramInPackage(final Package pkg, String name, final EaDiagramType type) {
        if (name == null) {
            name = pkg.GetName();
        }
        for (final Diagram d : pkg.GetDiagrams()) {
            if (d.GetName()
                 .equals(name) && (type.toString()
                                       .equals(d.GetType()))) {
                return d;
            }
        }
        final Diagram newDiagram = pkg.GetDiagrams()
                                      .AddNew(name, type.toString());
        pkg.GetDiagrams()
           .Refresh();
        newDiagram.Update();
        pkg.Update();

        return newDiagram;
    }

    public DiagramObject findOrCreateDiagramObject(final Package pkg, final Diagram diagram, final Element reposElement) {
        for (final DiagramObject dObject : diagram.GetDiagramObjects()) {
            if (dObject.GetElementID() == reposElement.GetElementID()) {
                return dObject;
            }
        }
        final DiagramObject diagramObject = diagram.GetDiagramObjects()
                                                   .AddNew("", "");
        diagramObject.SetInstanceID(reposElement.GetElementID());
        diagramObject.SetElementID(reposElement.GetElementID());
        diagramObject.Update();
        diagram.Update();
        pkg.GetDiagrams()
           .Refresh();
        pkg.Update();
        return diagramObject;
    }

    /**
     * @param from the source/originator of the link, aka the "Supplier" in EA terms.
     * @param to   the target/destination of the link, aka the "Client" in EA terms.
     * @param name name of the link. Used to look up already existing links.
     * @return
     */
    public Connector findOrCreateLink(final Element from, final Element to, final String name) {
        // check for existence
        for (final Connector c : to.GetConnectors()) {
            if (c.GetName()
                 .equals(name)) {
                if ((c.GetSupplierID() == to.GetElementID()) && (c.GetClientID() == from.GetElementID())) {
                    return c;
                }
            }
        }

        final Connector c = to.GetConnectors()
                              .AddNew(name, ASSOCIATION.toString());
        c.SetSupplierID(to.GetElementID());
        if (!c.Update()) {
            LOG.error("Unable to update connector to: " + to.GetName());
            return null;
        }
        to.GetConnectors()
          .Refresh();

        c.SetClientID(from.GetElementID());
        if (!c.Update()) {
            LOG.error("Unable to update connector from: " + from.GetName());
            return null;
        }
        from.GetConnectors()
            .Refresh();

        c.SetDirection(EaLinkDirection.SOURCE_DESTINATION.toString());
        c.Update();

        from.Update();
        to.Update();
        return c;
    }


    /**
     * Find UML Object elements inside a specific UML Package.
     * Non-recursive, searches the top-level (given) package only.
     *
     * @param pack the Package to serach in.
     * @return
     */
    public List<Element> findObjectsInPackage(final EaPackage pack) {
        return findElementsOfTypeInPackage(pack, OBJECT);
    }

    public boolean isOfType(final Element theObject, final Element classifier) {
        final int classifierId = theObject.GetClassifierID();
        if (classifier == null) {
            return (classifierId == 0);
        }

        return (classifier.GetElementID() == classifierId);
    }

    private Element addElementInPackage(final EaPackage pkg, final String name, final EaMetaType umlType, final Element classifier) {
        ensureRepoIsOpen();

        Package pack = pkg.unwrap();
        final Element element = pack.GetElements()
                                    .AddNew(name, umlType.toString());
        pack.GetElements()
            .Refresh();

        if (classifier != null) {
            element.SetClassifierID(classifier.GetElementID());
            element.SetClassifierName(classifier.GetName());
        }

        element.Update();
        pack.Update();
        pack.GetElements()
            .Refresh();
        return element;
    }

    public EaPackage findOrCreatePackage(final Package parent, final String name) {
        return findOrCreatePackage(new EaPackage(parent, this, null), name, NON_RECURSIVE);
    }

    private EaPackage findOrCreatePackage(final EaPackage parent, final String name, final boolean recursive) {
        ensureRepoIsOpen();
        final EaPackage pkg = findPackageByName(name, parent, recursive);
        if (pkg != null) {
            return pkg;
        }
        final Package pack = parent.unwrap()
                                   .GetPackages()
                                   .AddNew(name, PACKAGE.toString());
        pack.Update();
        final Package unwrapped = parent.unwrap();
        unwrapped
                .Update();
        unwrapped.GetPackages()
                 .Refresh();

        return new EaPackage(pack, this, parent);
    }


    /**
     * Looks for a subpackage with a given unqualified name within a given EA package. The
     * search is case sensitive. The first matching package ir returned performing a breadth-first search.
     * If more than one package with the same unqualified name exists within the repos, the result may
     * be ambiguous.
     *
     * @param theName   The unqualified package name to look for
     * @param rootPkg   The EA model root package to search within
     * @param recursive Set to true to do a recursive search in package hierarchy,
     *                  false to do a flat search at current level only
     * @return The Package object in the EA model, or null if package was not found.
     */
    EaPackage findPackageByName(final String theName, final EaPackage rootPkg, final boolean recursive) {
        ensureRepoIsOpen();

        if (rootPkg == null) {
            return null;
        }

        if (!packageCache.isEmpty()) {
            return packageCache.findPackageByName(rootPkg, theName, packagePattern, recursive);
        } else {
            return findPackageByNameNoCache(theName, rootPkg, recursive);
        }
    }

    private EaPackage findPackageByNameNoCache(final String theName, final EaPackage rootPkg, final boolean recursive) {
        for (final Package pkg : rootPkg.unwrap()
                                        .GetPackages()) {
            final EaPackage child = new EaPackage(pkg, this, rootPkg);
            if (pkg.GetName()
                   .equals(theName) && packageMatchNoCache(pkg)) {
                return child;
            }
            if (recursive) {
                final EaPackage nextPkg = findPackageByNameNoCache(theName, child, true);

                if (nextPkg != null) {
                    // Found it
                    return nextPkg;
                }
            }
        }
        // No match
        return null;
    }

    /**
     * Alternative name for better code readability internally in this class
     */

    private boolean ensureRepoIsOpen() {
        return open();
    }

    /**
     * Open the Enterprise Architect model repository.
     */
    public boolean open() {
        if (isOpen) {
            return true;
        }
        try {
            reposString = reposFile.getAbsolutePath();
            final String[] reposStrings = reposString.split("db:");
            if (reposStrings.length >= 2) {
                reposString = reposStrings[1];
            }
            LOG.info("Opening model repository: [{}]", reposString);
            LOG.debug("Before new repos [{}]", new Date());
            repository = new Repository();
            LOG.debug("After new repos [{}]", new Date());
            repository.SetSuppressEADialogs(true);
            repository.SetSuppressSecurityDialog(true);
            repository.SetEnableCache(true);
            if (EA_USERNAME.exists() && EA_PASSWORD.exists()) {
                final String username = EA_USERNAME.value();
                final String pwd = EA_PASSWORD.value();
//            log.debug("Username/pwd : [" + username + "]:[" + pwd + "]" );
                repository.OpenFile2(reposString, username, StringUtils.trimToEmpty(pwd));
            } else {
                repository.OpenFile(reposString);
            }
            LOG.debug("After open [{}]", new Date());
            isOpen = true;
        } catch (final Exception e) {
            e.printStackTrace();
            LOG.error(e.toString());
            final String msg = "An error occurred. This might be caused by an incorrect diagramgen-repo connect string.\n" +
                    "Verify that the connect string in the ea.application.properties file is the same as\n" +
                    "the connect string that you can find in Enterprise Architect via the File->Open Project dialog";
            System.out.println(msg);
            return false;
        }
        return true;
    }

    /**
     * @param namespaceURI
     * @return
     */
    public EaPackage findOrCreatePackageFromNamespace(final String namespaceURI) {
        ensureRepoIsOpen();
        LOG.debug("Looking for package with namespace: [{}]", namespaceURI);

        // todo implement
        return findPackageByName("Klasser", true);
    }

    /**
     * Looks for a subpackage with a given unqualified name within a given EA package. The
     * search is case sensitive. The first matching package ir returned performing a breadth-first search.
     * If more than one package with the same unqualified name exists within the repos, the result may
     * be ambiguous. The search is always performed from the root of the repos.
     *
     * @param theName   The unqualified package name to look for
     * @param recursive Set to true to do a recursive search in package hierarchy,
     *                  false to do a flat search at current level only
     * @return The Package object in the EA model, or null if package was not found.
     */
    public EaPackage findPackageByName(final String theName, final boolean recursive) {
        return findPackageByName(theName, getRootPackage(), recursive);
    }

    public Element findXsdType(final Package pkg, final String xsdTypeName) {
        final String stereotype = pkg.GetStereotypeEx();
        final String pkgString = pkg.GetName();

        if (stereotype.equals(xsdSchemaStereotype)) {
            LOG.info("Looking for [{}]  inside  package [{}]", xsdTypeName, pkgString);
            for (final Element element : pkg.GetElements()) {
                if (element.GetName()
                           .equals(xsdTypeName)) {
                    return element;
                }
            }
        } else {
            // Loop through all subpackages in EA model pkg
            for (final Package aPackage : pkg.GetPackages()) {
                final Element element = findXsdType(aPackage, xsdTypeName);
                if (element != null) {
                    return element;
                }
            }
        }
        return null;
    }

    /**
     * Generate all diagrams from the model into the directory path.
     * The package structure of the model is retained as directory structure.
     * All existing diagrams are overwritten.
     */
    public int generateAllDiagramsFromRoot() {
        if (packageCache.isEmpty()) {
            packageCache.populate(this, getRootPackage(), getRootPackage());
        }
        final IntCounter count = new IntCounter();
        generateAllDiagramsRecursive(getRootPackage(), count);
        return count.count;
    }

    /**
     * Generate all diagrams from the model into the directory path.
     * The package structure of the model is retained as directory structure.
     * All existing diagrams are overwritten.
     */
    public int generateAllDiagramsFromAllRoots() {
        final List<EaPackage> allRoots = findAllRootPackages();
        int totalCount = 0;
        for (final EaPackage root : allRoots) {
            rootPackage = root;
            packageCache.clear();
            totalCount += generateAllDiagramsFromRoot();
        }
        return totalCount;
    }

    /**
     * Recursive method that finds all diagrams in a package recursively and writes them to file.
     *
     * @param pkg
     * @param diagramCount
     */
    public void generateAllDiagramsRecursive(final EaPackage pkg, final IntCounter diagramCount) {
        generateAllDigramsInPackage(pkg, diagramCount);
        for (final EaPackage eaPackage : packageCache.findFamilyOf(pkg)) {
            if (generateAllDigramsInPackage(eaPackage, diagramCount)) return;
        }
    }

    private boolean generateAllDigramsInPackage(final EaPackage pkg, final IntCounter diagramCount) {
        if (!packageCache.packageMatch(pkg.unwrap(), packagePattern)) {
            LOG.info("--- Skipping package [{}] Applied filter: [{}]", pkg.getName(), packagePattern.pattern());
            return true;
        }

        final List<EaDiagram> diagrams = findDiagramsInPackage(pkg);
        if (!diagrams.isEmpty()) {
            LOG.debug("Generating diagrams in package: [{}]", pkg.getName());
            diagramCount.count = diagramCount.count + diagrams.size();
            for (final EaDiagram eaDiagram : diagrams) {
                final String diagramUrl = eaDiagram.writeImageToFile(false);
                LOG.debug("Generated diagram: [{}] with url: [{}]", eaDiagram.getName(), diagramUrl);
                repository.CloseDiagram(eaDiagram.getDiagramID()); // Try to avoid the 226 bug.
            }
        }
        return false;
    }

    /**
     * Find all UML diagrams inside a specific Package. Non-recursive, searches the top-level (given)
     * package only.
     *
     * @param pack the Package to search in.
     * @return
     */
    public List<EaDiagram> findDiagramsInPackage(final EaPackage pack) {
        if (pack == null || pack.unwrap() == null) {
            return Collections.emptyList();
        }
        final Package pkg = pack.unwrap();
        final List<EaDiagram> result = new ArrayList<>();
        final Collection<Diagram> diagrams;
        try {
            diagrams = pkg.GetDiagrams();
        } catch (final Exception e) {
            LOG.error("Fuckup in diagram package", e);
            return Collections.emptyList();
        }
        if (diagrams == null) {
            LOG.error("Fuckup in diagram package [{}]", pkg.GetName());
            return Collections.emptyList();
        }
        for (final Diagram d : diagrams) {
            result.add(new EaDiagram(this, d, getPackagePath(pkg)));
        }
        for (final Element element : pkg.GetElements()) {
            findDiagramsInElements(pkg, element, result);
        }
        return result;
    }

    /**
     * Creates path on the form /a/b/c
     *
     * @param pkg
     * @return
     */
    public String getPackagePath(final Package pkg) {
        final ArrayList<Package> ancestorPackages = new ArrayList<>();
        getAncestorPackages(ancestorPackages, pkg);
        final StringBuilder pathName = new StringBuilder();
        Collections.reverse(ancestorPackages);
        for (final Package p : ancestorPackages) {
            pathName.append(NameNormalizer.URL_SEPARATOR)
                    .append(p.GetName());
        }
        return pathName.toString();
    }

    private void getAncestorPackages(final ArrayList<Package> ancestorPackages, final Package pkg) {
        if (pkg == null) {
            return;
        }
        ancestorPackages.add(pkg);
        if (pkg.GetParentID() != 0) {
            getAncestorPackages(ancestorPackages, findPackageByID(pkg.GetParentID()));
        }
    }

    public boolean packageMatch(final Package p) {
        if (packageCache.isEmpty()) {
            return packageMatchNoCache(p);
        } else {
            return packageCache.packageMatch(p, packagePattern);
        }
    }

    private boolean packageMatchNoCache(final Package p) {
        if (p == null) {
            return false;
        }

        if (packagePattern == null) {
            return true;
        }
        final Matcher matcher = packagePattern.matcher(p.GetName());
        if (matcher.matches()) {
            LOG.debug("Package match : [{}]", p.GetName());
            return true;
        }
        LOG.debug("Looking for parent match for [{}] ", p.GetName());
        return packageMatchNoCache(findPackageByIdNoCache(p.GetParentID()));
    }

    public Package findPackageByID(final int packageID) {
        final EaPackage eaPackageByID = findEaPackageByID(packageID);
        return eaPackageByID != null ? eaPackageByID.unwrap() : null;
    }

    public EaPackage findEaPackageByID(final int packageID) {
        if (packageID == 0) {
            // id=0 means this is the root
            return null;
        }
        LOG.info("Looking for package with id [{}] ", packageID);
        if (packageCache.isEmpty()) {
            ensureRepoIsOpen();
            return new EaPackage(findPackageByIdNoCache(packageID), this, null);
        } else {
            final EaPackage eaPackage = packageCache.findById(packageID);
            LOG.info("Found package [{}] in cache", eaPackage != null ? eaPackage.getName() : "not found...");
            return eaPackage;
        }
    }

    public Package findPackageByIdNoCache(final int packageId) {
        return repository.GetPackageByID(packageId);
    }

    /**
     * Some diagrams may reside below elements
     *
     * @param pkg
     * @param element
     * @param diagramList
     */
    public void findDiagramsInElements(final Package pkg, final Element element, final List<EaDiagram> diagramList) {
        if (element == null || element.GetElements() == null) {
            return;
        }
        for (final Element child : element.GetElements()) {
            findDiagramsInElements(pkg, child, diagramList);
        }
        for (final Diagram diagram : element.GetDiagrams()) {
            diagramList.add(new EaDiagram(this, diagram, getPackagePath(pkg)));
        }
    }

    public void generateHtml(final String path) {
        System.out.println("Generating HTML doc for package " + getRootPackage().getName() + " to " + path);
        repository.GetProjectInterface()
                  .RunHTMLReport(getRootPackage().unwrap()
                                                 .GetPackageGUID(), path, "PNG", "<default>", ".html");
    }

    /**
     * Generates XSD schema file for the package if its UML stereotype is <<XSDschema>>,
     * otherwise a subdirectory corresponding to the UML package is created in
     * directory and the method is called recursively for all its subpackages.
     *
     * @param directory The file system directory for generation
     * @param pkg       The EA model package to process
     */
    public void generateXSD(final File directory, final Package pkg, final String fileSeparator) {
        final Project eaProj = getProject();

        final String stereotype = pkg.GetStereotypeEx();
        final String pkgString = pkg.GetName();

        if (stereotype.equals(xsdSchemaStereotype)) {
            LOG.info("Generate XSD for package [{}]", pkgString);
            eaProj.GenerateXSD(pkg.GetPackageGUID(), directory.getAbsolutePath() + fileSeparator + pkgString + ".xsd", xmlEncoding, null);
        } else {
            // Create subdirectory in generation directory
            final File f = new File(directory, pkgString);

            if (f.mkdirs()) {
                LOG.debug("New subdir at: " + f.getAbsolutePath());
            }

            // Loop through all subpackages in EA model pkg
            for (final Package aPackage : pkg.GetPackages()) {
                generateXSD(f, aPackage, fileSeparator);
            }
        }
    }

    public Project getProject() {
        return project;
    }

    /**
     * Just an early test method to display the internal EA data types
     *
     * @return
     */
    public String getEaDataTypes() {
        ensureRepoIsOpen();

        final StringBuilder sb = new StringBuilder();
        final Collection<Datatype> dataTypes = repository.GetDatatypes();
        for (final Datatype dt : dataTypes) {
            sb.append(dt.GetName())
              .append(", ");
        }
        return sb.toString();
    }

    /**
     * todo do we need this, then code it right...
     *
     * @param object
     * @param name
     * @param value
     */
    public void setAttributeValue(final Element object, final String name, final String value) {
        ensureRepoIsOpen();
        // @VAR;Variable=name;Value=mittNavnPaaObjekt;Op==;@ENDVAR;@VAR;Variable=attribEn;Value=enverdi;Op==;@ENDVAR;
        object.SetRunState("@VAR;Variable=name;Value=dittnavn;Op==;@ENDVAR;");
        object.Update();
    }

    public EaDiagram createOrUpdateStandardDiagram(final EaElement centralElement) {
        final EaMetaType metaType = centralElement.getMetaType();
        if (!metaTypesThatHasDiagrams.contains(metaType)) {
            LOG.warn("No diagram is generated for [{}] of metaType [{}]. Has to be one of {}", centralElement.getName(), metaType,
                     metaTypesThatHasDiagrams);
            return null;
        }

        final String diagramName = EaDiagram.createStandardDiagramName(centralElement);

        EaDiagram eaDiagram = centralElement.findDiagram(diagramName);
        try {
            if (eaDiagram == null) {
                final Package pack = findPackageByID(centralElement.getPackageID());
                final Collection<Diagram> diagrams = pack.GetDiagrams();
                final Diagram diagram = diagrams.AddNew(diagramName, EaDiagramType.COMPONENT.toString());
                pack.Update();
                repository.SaveDiagram(diagram.GetDiagramID());
                diagrams.Refresh();
                eaDiagram = new EaDiagram(this, diagram, getPackagePath(pack));
                eaDiagram.setParentId(centralElement.getId());
                LOG.info("Created diagram [{}] below [{}]", diagramName, centralElement.getName());
                eaDiagram.setStatus(EaDiagram.Status.NEW);
            } else {
                eaDiagram.removeAllElements();
                LOG.info("Removed elements from diagram [{}]", eaDiagram.getName());
                eaDiagram.setStatus(EaDiagram.Status.UPDATED);
            }
        } catch (final Exception e) {
            LOG.error("Unable to create or update diagram [{}] because: ", eaDiagram != null ? eaDiagram : diagramName, e);
            return null;
        }
        boolean success = false;
        success = eaDiagram.layoutAndSaveDiagram(centralElement);
        if(success) {
            ++noOfDiagramsCreated;
        }
        return success ? eaDiagram : null;
    }

    public boolean saveDiagram(final EaDiagram eaDiagram) {
        try {
            repository.SaveDiagram(eaDiagram.getDiagramID());
            return true;
        } catch (final Exception e) {
            LOG.error("Unable to save diagram [{}] because [{}]", eaDiagram, e);
        }
        return false;
    }

    // Check if element shall be exported to file
    public boolean doGenerate(final EaElement eaElement) {
        return ((eaElement.getMetaType() == QUEUE) || isBlank(eaElement.getClassifierType()))
                && (previousElements.get(eaElement.getElementGUID()) == null)
                && filterMatches(elementPattern, eaElement.getName())
                && filterMatches(stereotypeFilter, eaElement.getStereotypeEx());
    }

    private boolean filterMatches(final Pattern pattern, final String paramToMatch) {
        return pattern == null || pattern.matcher(paramToMatch)
                                         .matches();
    }

    /**
     * Populate cache if not already done, then find the package.
     *
     * @param hierarchicalPackageName hierarchical name of a package, e.g. "A->subPack->child"
     * @return null if no package for path is found
     */
    public EaPackage findInPackageCache(final String hierarchicalPackageName) {
        LOG.info("Finding local root [{}]", hierarchicalPackageName);
        if (packageCache.isEmpty()) {
            packageCache.populate(this, getRootPackage(), getRootPackage());
        }
        final EaPackage localRoot = packageCache.findPackageByHierarchicalName(getRootPackage(), hierarchicalPackageName, packagePattern);
        LOG.info("Found [{} local root for [{}] from [{}] using pattern [{}]", localRoot == null ? "no]" : localRoot + "] as ",
                 hierarchicalPackageName, getRootPackage()
                         .getHierarchicalName(), packagePattern == null ? "no" : packagePattern);
        return localRoot;
    }

    /**
     * Find all immediate children packages of pkg
     *
     * @param pkg
     * @return
     */
    public List<EaPackage> findPackages(final EaPackage pkg) {
        final List<EaPackage> result = new ArrayList<>();
        if (packageCache.isEmpty()) {
            for (final Package aPackage : pkg.unwrap()
                                             .GetPackages()) {
                result.add(new EaPackage(aPackage, this, pkg));
            }
            return result;
        }
        return packageCache.findChildrenOf(pkg);
    }

    /**
     * Find all descendants of pkg
     *
     * @param pkg
     * @return
     */
    public List<EaPackage> findAllPackages(final EaPackage pkg) {
        if (packageCache.isEmpty()) {
            throw new UnsupportedOperationException("Cache is not populated");
        }
        return packageCache.findFamilyOf(pkg);
    }

    public Boolean createBaseline(final String packageGuid, final String versionNo, final String notes) {
        return project.CreateBaseline(packageGuid, versionNo, notes);
    }

    public void clearPackageCache() {
        packageCache.clear();
    }

    public int getNoOfDiagramsCreated() {
        return noOfDiagramsCreated;
    }

    public List<EaElement> findElementsInPackage(final String pack, final String elementName) {
        final List<EaElement> result = new ArrayList<>();
//        final LinkedList<String> hier = EaPackage.hierarchyToList(pack);
        final Collection<Element> elements = repository.GetElementsByQuery("Simple", elementName);
        final EaPackage requiredPackage = packageCache.findPackageByHierarchicalName(rootPackage, pack, null);
        for (final Element element : elements) {

//            final Package elementPackage = repository.GetPackageByID(element.GetPackageID());
            // Simple search returns partial name match
            if(elementName.equalsIgnoreCase(trimToEmpty(element.GetName())) && requiredPackage.getId() == element.GetPackageID()) {
//            final EaPackage eaPackage = new EaPackage(elementPackage, this, null);
//            eaPackage.getHierarchicalName()
//            if (hier.contains(elementPackage.GetName()) && element.GetName().equals(elementName)) {
                result.add(new EaElement(element, this));
            }
        }
        return result;
    }

    private boolean matchPackageToHierarchy() {
        return true;
    }
}
