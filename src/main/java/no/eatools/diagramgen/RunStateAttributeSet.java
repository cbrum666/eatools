package no.eatools.diagramgen;

import java.util.HashMap;
import java.util.Map;

import no.bouvet.ohs.jops.SystemProperties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @author AB22273
 * @date 05.nov.2008
 * @since 05.nov.2008 15:08:02
 */
public class RunStateAttributeSet {
    private static final Log log = LogFactory.getLog(RunStateAttributeSet.class);

    private Map<String, RunStateAttribute> theAttributes = new HashMap<String, RunStateAttribute>();

    /**
     * @param xmlAttributes xml-fragment as produced from xmlBean-instances.
     */
    public RunStateAttributeSet(final String xmlAttributes) {
        String allAttribs = xmlAttributes.replaceAll("\\<[^ ]* ", "");
        allAttribs = allAttribs.replaceAll("xmlns:xsi.*", "");

        final String[] singleAttributes = allAttribs.split("\" ");
        for (final String single : singleAttributes) {
            final RunStateAttribute runStateAttribute = new RunStateAttribute(single);
            theAttributes.put(runStateAttribute.getName(), runStateAttribute);
        }
    }

    /**
     * Remove attributes with names on the filteredAttributes list.
     *
     * @param filteredAttributes the list of attributes to remove from the RunStateAttributeSet.
     */
    @SuppressWarnings({"VariableArgumentMethod"})
    public void filter(final String... filteredAttributes) {
        for (final String filteredAtt : filteredAttributes) {
            final RunStateAttribute runStateAttribute = theAttributes.get(filteredAtt);
            if (runStateAttribute != null) {
                theAttributes.remove(runStateAttribute.getName());
            }
        }
    }

    /**
     * Create a String that is suitable for the RunState setting in EA.
     *
     * @return EA-compliant RunState string.
     */
    public String toRunState() {
        final StringBuilder sb = new StringBuilder();
        for (final RunStateAttribute runStateAttribute : theAttributes.values()) {
            sb.append(runStateAttribute.toRunState());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (final RunStateAttribute runStateAttribute : theAttributes.values()) {
            sb.append(runStateAttribute.toString()).append(SystemProperties.LINE_SEPARATOR.value());
        }
        return sb.toString();
    }

    public RunStateAttribute get(final String key) {
        return theAttributes.get(key);
    }
}
