package no.eatools.util;

import no.bouvet.ohs.jops.Description;
import no.bouvet.ohs.jops.EnumProperty;
import no.bouvet.ohs.jops.PropertyMap;
import no.bouvet.ohs.jops.SystemPropertySet;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * This class handles the properties that can be used to configure the EA utilities. The property file can:
 * <ul>
 * <li>
 * Be given as a parameter, or
 * </li>
 * <li>
 * Be placed in a file called 'ea.application.properties' (i.e. corresponding to the classname) which
 * must be located in {user.home} or in classpath.
 * </li>
 * </ul>
 * <p>
 * The order of precedence is: parameter, file in {user.home}, file in classpath.
 * </p>
 * The names of the properties in the property file must correspond to the constants defined in
 * this enum class, and all enum-constants must have a corresponding property in the file.
 *
 * @author Ove Scheel
 * @author Per Spilling
 * @since 05.nov.2008 09:25:13
 */
public enum EaApplicationProperties implements EnumProperty {
    @Description(text = "Name of .eap file or connection string to database repos.", mandatory = true)
    EA_PROJECT(),

    @Description(text = "The root package in the repo to generate from. Must be a top level package.")
    EA_ROOTPKG(),

    @Description(text = "The directory root to place diagrams in when generating the diagrams.\nNB! Must be given as an absolute pathname or "
            + "relative to cwd.")
    EA_DOC_ROOT_DIR() {
        @Override
        public String value() {
            return FilenameUtils.normalize(appendIfMissing(super.value(), SystemPropertySet.FILE_SEPARATOR.value()));
        }
    },

    @Description(text = "The loglevel when running the utility.", defaultValue = "INFO")
    EA_LOGLEVEL(),

    @Description(text = "Name or diagramId (internal EA number) of diagram to generate.")
    EA_DIAGRAM_TO_GENERATE(),

    @Description(text = "Which file to place the url of the generated diagram. This file may be used for scripting after generation or as a "
            + "debugging aid.")
    EA_DIAGRAM_URL_FILE(),

    @Description(text = "Only include packages which matches given regexp. Applies to packages above given specified export package.")
    EA_PACKAGE_FILTER(),

    @Description(text = "Only include elements which matches given regexp.")
    EA_ELEMENT_FILTER(),

    @Description(text = "Only include elements has a stereotype matching given regexp.")
    EA_STEREOTYPE_FILTER(),

    @Description(text = "Top level package for commands that require a starting package.")
    EA_TOP_LEVEL_PACKAGE("Must be set"),

    @Description(text = "Username for EA repos.")
    EA_USERNAME(),

    @Description(text = "Password for EA repos.")
    EA_PASSWORD(),

    @Description(text = "If present, add diagram version as part of diagram filename.")
    EA_ADD_VERSION(),

    @Description(text = "For -m option, generate HTML to this path.")
    EA_HTML_OUTPUT(),

    @Description(text = "Base URL for shared store of diagram files. Used for generating list of files. E.g. 'http://images.mysite.org/'")
    EA_URL_BASE() {
        @Override
        public String value() {
            final String value = super.value();
            return isBlank(value) ? EMPTY : appendIfMissing(value, NameNormalizer.URL_SEPARATOR);
        }
    },

    @Description(text = "Depth of diagram hierarchy")
    EA_DIAGRAM_NAME_LEVEL(),

    @Description(text = "Set to 'true' if AUTO diagrams for exported entities shall be generated", defaultValue = "false")
    EA_AUTO_DIAGRAM_GENERATE(),

    @Description(text = "Or together the desired flags, See http://www.sparxsystems.com/enterprise_architect_user_guide/9.3/automation/project_2.html", defaultValue = "0xFFFFFFFF")
    EA_AUTO_DIAGRAM_OPTIONS(),

    @Description(text = "See http://www.sparxsystems.com/enterprise_architect_user_guide/9.3/automation/project_2.html", defaultValue = "4")
    EA_AUTO_DIAGRAM_ITERATIONS(),

    @Description(text = "See http://www.sparxsystems.com/enterprise_architect_user_guide/9.3/automation/project_2.html", defaultValue = "20")
    EA_AUTO_DIAGRAM_LAYER_SPACING(),

    @Description(text = "See http://www.sparxsystems.com/enterprise_architect_user_guide/9.3/automation/project_2.html", defaultValue = "20")
    EA_AUTO_DIAGRAM_COLUMN_SPACING(),

    @Description(text = "Size of the interface (Lollipop) symbol on auto diagrams (px)", defaultValue = "100")
    EA_AUTO_DIAGRAM_INTERFACE_SIZE(),

    @Description(text = "Name of a Json file that contains elements to exclude from export", defaultValue = "")
    EA_EXCLUDE_FILE(),

    @Description(text = "TimeZone for the EA database server", defaultValue = "Europe/Oslo")
    EA_SERVER_TIMEZONE(),

    @Description(text = "How to name diagram paths")
    EA_DIAGRAM_NAME_MODE() {
        @Override
        public Class getType() {
            return DiagramNameMode.class;
        }

        @Override
        public void setValue(String value) {
            setValue(DiagramNameMode.class, value);
        }

        @Override
        public DiagramNameMode valueAsEnum() {
            return (DiagramNameMode) valueAs(DiagramNameMode.class);
        }
    };



    private static final transient Logger log = LoggerFactory.getLogger(EaApplicationProperties.class);


    private static PropertyMap<EaApplicationProperties> propsMap = new PropertyMap<>(EaApplicationProperties.class);
    private final String message;

    EaApplicationProperties(String message) {
        this.message = message;
    }

    EaApplicationProperties() {
        this.message = "";
    }

    @Override
    public PropertyMap<? extends EnumProperty> getPropertyMap() {
        return propsMap;
    }

    public static PropertyMap<EaApplicationProperties> getThePropertyMap() {
        return propsMap;
    }

    public String getMessage() {
        return keyAsPropertyName() + ":" + message;
    }

    public String getKeyValue() {
        return keyAsPropertyName() + "=" + value();
    }

    public static void reset() {
        propsMap.resetAll();
    }

}
