package no.eatools.diagramgen;

import org.sparx.Method;
import org.sparx.Parameter;

/**
 * @author ohs
 */
public class EaMethod {
    private final EaElement owner;
    private final Method theMethod;


//    public EaMethod(EaElement owner, String methodName, String returnType) {
//        theMethod = owner.addMethod(methodName, returnType);
//
//    }

    public EaMethod(final EaElement owner, final Method method) {
        this.owner = owner;
        this.theMethod = method;
    }

    public Parameter addParameter(final String name, final String type) {
        final Parameter par = theMethod.GetParameters()
                                       .AddNew(name, type);
        par.Update();
        theMethod.GetParameters().Refresh();
        return par;
    }
}
