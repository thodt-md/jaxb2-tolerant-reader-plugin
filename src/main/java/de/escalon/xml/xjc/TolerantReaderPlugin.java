package de.escalon.xml.xjc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlSeeAlso;
import javax.xml.bind.annotation.XmlType;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;

import com.sun.codemodel.JAnnotationArrayMember;
import com.sun.codemodel.JAnnotationUse;
import com.sun.codemodel.JAnnotationValue;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JFormatter;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JType;
import com.sun.codemodel.JVar;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CCustomizations;
import com.sun.tools.xjc.model.CPluginCustomization;
import com.sun.tools.xjc.model.CPropertyInfo;
import com.sun.tools.xjc.outline.ClassOutline;
import com.sun.tools.xjc.outline.Outline;
import com.sun.xml.xsom.XSComponent;
import com.sun.xml.xsom.impl.util.SchemaWriter;

import de.escalon.hypermedia.hydra.mapping.Expose;

// TODO find a way to introduce the patch to CClassInfo would it work to include the class itself?
// TODO try with other plugins, e.g. fluent builder
// TODO execute tests with /tests project automatically, add assertions
// TODO automatically keep required fields or attributes
// TODO automatically adjust getter and setter names for alias beans according to alias
// beans
// TODO annotate @Expose on class
// TODO package-info prefix annotation, currently we expose with full url and use prefix
// TODO decouple structurally: support EL for property paths to aliases, or xpath or use
// https://blog.frankel.ch/customize-your-jaxb-bindings/ converter method
// TODO create XSD Schema for tr extensions
// TODO use renamed namespace without version part for Expose (xjc renames packages, this
// TODO would require introducing a namespace-rename feature)
// TODO add getter Javadoc which allows to tell where property comes from, have XCD XPointer
// syntax there

/**
 * What we already achieve is decoupling names. If the service just renames an element or attribute,
 * we can handle it. What we don't do yet is decoupling structurally. Decoupling structurally means
 * having an alias whose source is not defined by a simple property:
 * 
 * <pre>
 * &lt;tr:alias property="risikoschluessel"&gt;riskKey&lt;/tr:alias&gt;
 * </pre>
 * 
 * but by a recipe to convert. Converting means:
 * <ul>
 * <li>reading a single value from an xpath in the current document
 * <li>do calculations with multiple xpaths
 * <li>in the extreme case creating a whole bean from several schema types and fill it from xpaths
 * <li>alongside a new property which reflects the structural change
 * </ul>
 * 
 * We should at least prove that an xpath based conversion works, along these lines
 * 
 * <pre>
 * &lt;tr:alias xpath="//risikoschluessel"&gt;riskKey&lt;/tr:alias&gt;
 * </pre>
 */
public class TolerantReaderPlugin extends Plugin {

    private static final Set<String> IGNORED_ANNOTATIONS = new HashSet<String>(
            Arrays.asList(XmlSeeAlso.class.getName(),
                    XmlAccessorType.class.getName()));

    private static final String NAMESPACE_URI = "http://jaxb2-commons.dev.java.net/tolerant-reader";

    /**
     * Name of Option to enable this plugin
     */
    private static final String OPTION_NAME = "Xtolerant-reader";

    private static final boolean HYDRA_PRESENT = ClassHelper.isPresent("de.escalon.hypermedia.hydra.mapping.Expose");

    /**
     * Creates a new <code>DefaultValuePlugin</code> instance.
     */
    public TolerantReaderPlugin() {
    }

    /**
     * DefaultValuePlugin uses "-Xtolerant-reader" as the command-line argument
     */
    public String getOptionName() {
        return OPTION_NAME;
    }

    /**
     * Return usage information for plugin
     */
    public String getUsage() {
        return "  -" + OPTION_NAME + "    : restricts xjc compilation to classes and properties named in bindings file";
    }

    @Override
    public List<String> getCustomizationURIs() {
        return Arrays.asList(NAMESPACE_URI);
    }

    @Override
    public boolean isCustomizationTagName(String nsUri, String localName) {
        return NAMESPACE_URI.equals(nsUri) && ("include".equals(localName)
                || "alias".equals(localName)
                || "bean".equals(localName));
    }

    @Override
    public boolean run(Outline outline, Options opts, ErrorHandler errHandler) throws SAXException {

        processSchemaTags(outline);

        // for( ClassOutline co : outline.getClasses() ) {
        // processClassTags(co);
        //
        // FieldOutline fos[] = co.getDeclaredFields();
        // for (FieldOutline fo : fos) {
        // processPropertyTags(fo);
        // }
        // }

        return true;
    }

    private static class SimpleNamespaceContext implements NamespaceContext {

        Map<String, String> namespaces;
        Map<String, String> prefixes;

        private SimpleNamespaceContext(Map<String, String> namespaces, Map<String, String> prefixes) {
            super();
            this.namespaces = namespaces;
            this.prefixes = prefixes;
        }

        @SuppressWarnings({ "rawtypes" })
        public Iterator getPrefixes(String namespaceURI) {
            return Collections.singleton(namespaces.get(namespaceURI))
                .iterator();
        }

        public String getPrefix(String namespaceURI) {
            return namespaces.get(namespaceURI);
        }

        public String getNamespaceURI(String prefix) {
            return prefixes.get(prefix);
        }
    }

    private static final class SimpleNamespaceContextBuilder {
        Map<String, String> namespaces = new HashMap<String, String>();
        Map<String, String> prefixes = new HashMap<String, String>();

        private static SimpleNamespaceContextBuilder aSingleNamespaceContext() {
            return new SimpleNamespaceContextBuilder();
        }

        SimpleNamespaceContextBuilder withNs(String prefix, String nsURI) {
            this.namespaces.put(nsURI, prefix);
            this.prefixes.put(prefix, nsURI);
            return this;
        }

        NamespaceContext build() {
            return new SimpleNamespaceContext(namespaces, prefixes);
        }

    }

    class BeanInclusions implements Iterable<List<BeanInclusion>> {
        Map<String, List<BeanInclusion>> beanInclusions;

        public BeanInclusions(Map<String, List<BeanInclusion>> beanInclusions) {
            this.beanInclusions = beanInclusions;
        }

        public BeanInclusion getBeanInclusion(CClassInfo classInfo) {
            List<BeanInclusion> beanInclusionList = beanInclusions.get(classInfo.shortName);
            if (beanInclusionList != null) {
                for (BeanInclusion beanInclusion : beanInclusionList) {
                    if (beanInclusion.includesClass(classInfo.getName())) {
                        return beanInclusion;
                    }
                }
            }
            return null;
        }

        public Iterator<List<BeanInclusion>> iterator() {
            return beanInclusions.values()
                .iterator();
        }
    }

    class BeanInclusion {
        private final String simpleName;
        private final Set<String> properties;
        private final String packageRoot;
        private final String trailingName;
        private HashMap<String, String> aliases;
        private String beanAlias;
        private String prefix;

        public BeanInclusion(String simpleName, Set<String> properties, HashMap<String, String> propertyAliases,
                String packageRoot, String prefix) {
            this.simpleName = simpleName;
            this.aliases = propertyAliases;
            this.prefix = prefix;
            this.trailingName = "." + simpleName;
            this.properties = properties;
            this.packageRoot = packageRoot;
        }

        public String getPropertyAlias(String property) {
            return aliases.get(property);
        }

        public void setBeanAlias(String beanAlias) {
            this.beanAlias = beanAlias;
        }

        public String getBeanAlias() {
            return beanAlias;
        }

        public String getPrefix() {
            return prefix;
        }

        public boolean includesClass(String className) {
            return className.endsWith(trailingName) && (packageRoot == null ? true : className.startsWith(packageRoot));
        }

        public boolean includesProperty(String propertyName) {
            return properties.contains(propertyName);
        }

        @Override
        public String toString() {
            return (packageRoot.isEmpty() ? packageRoot : packageRoot + ".") + simpleName + " " + properties.toString()
                    + " aliases " + aliases.toString();
        }

        public boolean isSatisfiedByProperties(List<CPropertyInfo> propertyInfos) {
            for (String propertyName : properties) {
                CPropertyInfo found = findPropertyInfo(propertyInfos, propertyName);
                if (found == null) {
                    return false;
                }
            }
            return true;
        }

        private CPropertyInfo findPropertyInfo(List<CPropertyInfo> propertyInfos, String propertyName) {
            for (CPropertyInfo cPropertyInfo : propertyInfos) {
                if (propertyName.equals(cPropertyInfo.getName(false))) { // fooBar
                    return cPropertyInfo;
                }
            }
            return null;
        }

    }

    private void processSchemaTags(Outline outline) {
        CCustomizations customizations = outline.getModel()
            .getCustomizations();

        BeanInclusions beanInclusions = getBeanInclusions(customizations);

        Collection<? extends ClassOutline> classOutlines = outline.getClasses();

        // check that all included beans are present in classOutlines
        for (List<BeanInclusion> inclusionCandidates : beanInclusions) {
            for (BeanInclusion beanInclusion : inclusionCandidates) {
                ClassOutline found = findMatchingInclusionEntry(classOutlines, beanInclusion);
                if (found == null) {
                    throw new IllegalArgumentException(
                            "Tolerant reader expects bean " + inclusionCandidates.toString() + ", but schema has no such bean");
                }
                List<CPropertyInfo> ownAndInheritedProperties = new ArrayList<CPropertyInfo>(found.target
                    .getProperties());
                CClassInfo currentClass = found.target;
                while (null != (currentClass = currentClass.getBaseClass())) {
                    if (currentClass != null) {
                        ownAndInheritedProperties.addAll(currentClass.getProperties());
                    }
                }
                List<String> propertyNames = new ArrayList<String>();
                for (CPropertyInfo cPropertyInfo : ownAndInheritedProperties) {
                    propertyNames.add(cPropertyInfo.getName(false));
                }
                if (!beanInclusion.isSatisfiedByProperties(ownAndInheritedProperties)) {
                    throw new IllegalArgumentException("Tolerant reader expects " +
                            beanInclusion.toString()
                            + " but only found properties " + propertyNames + " in schema");
                }

            }
        }
        // collect things to keep
        Map<String, Set<String>> classesToKeep = getClassesToKeep(beanInclusions, classOutlines);

        performEditing(outline, beanInclusions, classOutlines, classesToKeep);
    }

    private void performEditing(Outline outline, BeanInclusions beanInclusions,
            Collection<? extends ClassOutline> classOutlines, Map<String, Set<String>> classesToKeep) {
        try {
            Map<ClassOutline, JDefinedClass> beansToRename = new HashMap<ClassOutline, JDefinedClass>();
            Map<String, String> beanAliases = new HashMap<String, String>();

            removeUnusedAndRenameProperties(outline, beanInclusions, classOutlines, classesToKeep);
            createAliasBeans(outline, beanInclusions, classOutlines, beansToRename, beanAliases);
            applyBeanAliasesToClasses(outline, beanInclusions, classOutlines, classesToKeep, beanAliases);
            fillAliasBeanContent(outline, classesToKeep, beansToRename, beanAliases);

            applyXmlTypeToClasses(classOutlines, beanInclusions, classesToKeep);
            applyXmlTypeToAliases(classOutlines, beanInclusions, classesToKeep, beansToRename);

            removeBeansWhichHaveAliases(outline, beanAliases);
        } catch (Exception e) {
            throw new RuntimeException("failed to edit class", e);
        }
    }

    private void applyXmlTypeToAliases(Collection<? extends ClassOutline> classOutlines, BeanInclusions beanInclusions,
            Map<String, Set<String>> classesToKeep, Map<ClassOutline, JDefinedClass> beansToRename) {

        for (Entry<ClassOutline, JDefinedClass> beanToRenameEntry : beansToRename.entrySet()) {
            final ClassOutline classOutline = beanToRenameEntry.getKey();
            CClassInfo classInfo = classOutline.target;
            String className = classInfo.getName();

            final Set<String> propertiesToKeep = classesToKeep.get(className);
            if (propertiesToKeep == null) {
                continue;
            }
            JDefinedClass implClass = beanToRenameEntry.getValue();

            // add XmlType with name and propOrder
            JAnnotationUse annotateXmlType = implClass.annotate(XmlType.class);
            annotateXmlType.param("name", classInfo.getTypeName()
                .getLocalPart());

            XSComponent schemaComponent = classInfo.getSchemaComponent();
            // namespace my typeName
            /** @see <a href="https://www.w3.org/TR/xmlschema-ref/">SCD</a> */
            String scd = "/type::my:" + classInfo.getTypeName()
                .getLocalPart() + "/model::sequence";
            NamespaceContext nsContext = SimpleNamespaceContextBuilder.aSingleNamespaceContext()
                .withNs("my", classInfo.getTypeName()
                    .getNamespaceURI())
                .build();
            Collection<XSComponent> select = schemaComponent.select(scd, nsContext);
            System.out.println(scd);
            for (XSComponent sequenceElement : select) {
                PropOrderVisitor visitor = new PropOrderVisitor(classOutline, beanInclusions, propertiesToKeep);
                sequenceElement.visit(visitor);
                JAnnotationArrayMember paramArray = annotateXmlType.paramArray("propOrder");
                for (String propOrderItem : visitor.getPropOrderList()) {
                    paramArray.param(propOrderItem);
                }
            }

        }
    }

    private void applyXmlTypeToClasses(Collection<? extends ClassOutline> classOutlines, BeanInclusions beanInclusions,
            Map<String, Set<String>> classesToKeep) throws IOException {
        for (final ClassOutline classOutline : classOutlines) {
            CClassInfo classInfo = classOutline.target;
            String className = classInfo.getName();

            final Set<String> propertiesToKeep = classesToKeep.get(className);
            if (propertiesToKeep == null) {
                continue;
            }
            JDefinedClass implClass = classOutline.implClass;

            // add XmlType with name and propOrder
            JAnnotationUse annotateXmlType = implClass.annotate(XmlType.class);
            annotateXmlType.param("name", classInfo.getTypeName()
                .getLocalPart());

            XSComponent schemaComponent = classInfo.getSchemaComponent();
            // namespace my typeName
            /** @see <a href="https://www.w3.org/TR/xmlschema-ref/">SCD</a> */
            String scd = "/type::my:" + classInfo.getTypeName()
                .getLocalPart() + "/model::sequence";
            NamespaceContext nsContext = SimpleNamespaceContextBuilder.aSingleNamespaceContext()
                .withNs("my", classInfo.getTypeName()
                    .getNamespaceURI())
                .build();
            Collection<XSComponent> select = schemaComponent.select(scd, nsContext);
            System.out.println(scd);

            for (XSComponent sequenceElement : select) {
                PropOrderVisitor visitor = new PropOrderVisitor(classOutline, beanInclusions, propertiesToKeep);
                sequenceElement.visit(visitor);
                JAnnotationArrayMember paramArray = annotateXmlType.paramArray("propOrder");
                for (String propOrderItem : visitor.getPropOrderList()) {
                    paramArray.param(propOrderItem);
                }
                OutputStreamWriter outputStreamWriter = new OutputStreamWriter(System.out);
                SchemaWriter schemaWriter = new SchemaWriter(outputStreamWriter);
                sequenceElement.visit(schemaWriter);
                outputStreamWriter.flush();
            }
        }
    }

    private void removeBeansWhichHaveAliases(Outline outline, Map<String, String> beanAliases) {
        for (String renamedBean : beanAliases.keySet()) {
            removeClass(outline, renamedBean);
        }
    }

    private void applyBeanAliasesToClasses(Outline outline, BeanInclusions beanInclusions,
            Collection<? extends ClassOutline> classOutlines, Map<String, Set<String>> classesToKeep,
            Map<String, String> beanAliases) throws ClassNotFoundException, IOException {

        for (ClassOutline classOutline : classOutlines) {
            CClassInfo classInfo = classOutline.target;
            JDefinedClass implClass = classOutline.implClass;

            addXmlSeeAlso(outline, classesToKeep, beanAliases, classInfo, implClass);
            updateAliasFieldsAndAccessors(outline, beanInclusions, beanAliases, classInfo, implClass);
        }
    }

    private void updateAliasFieldsAndAccessors(Outline outline, BeanInclusions beanInclusions,
            Map<String, String> beanAliases, CClassInfo classInfo, JDefinedClass implClass)
            throws ClassNotFoundException, IOException {
        Collection<JMethod> methods = implClass.methods();
        Map<String, JFieldVar> fields = implClass.fields();

        Map<String, JFieldVar> originalFields = new HashMap<String, JFieldVar>();
        originalFields.putAll(fields); // concurrent modification
        for (Entry<String, JFieldVar> entry : originalFields.entrySet()) {
            JFieldVar field = entry.getValue();
            String fieldName = entry.getKey();
            JType fieldType = field.type();
            CPropertyInfo propertyInfo = classInfo.getProperty(fieldName);
            if (propertyInfo == null) {
                continue;
            }
            String publicName = propertyInfo.getName(true);
            JClass aliasFieldType = getAliasFieldType(outline, beanAliases, fieldType);
            if (aliasFieldType == null) {
                applyExpose(propertyInfo.getName(false), Annotatable.from(ClassHelper.findGetterInClass(implClass,
                        publicName)),
                        beanInclusions, outline, classInfo);
            } else {
                // field
                implClass.removeField(field);
                JFieldVar aliasTypeField = implClass.field(field.mods()
                    .getValue(), aliasFieldType, fieldName);

                AnnotationHelper.applyAnnotations(outline, Annotatable.from(aliasTypeField), field.annotations());

                // getter
                JMethod getter = ClassHelper.findGetterInClass(implClass, publicName);
                if (getter != null) {
                    JMethod aliasedMethod = implClass.method(getter.mods()
                        .getValue(), aliasFieldType, getter.name());

                    JBlock body = aliasedMethod.body();
                    if (List.class.getName()
                        .equals(aliasFieldType.erasure()
                            .fullName())) {

                        JClass elementType = aliasFieldType.getTypeParameters()
                            .get(0);

                        body._if(field.eq(JExpr._null()))
                            ._then()
                            .assign(JExpr._this()
                                .ref(field), JExpr._new(outline.getCodeModel()
                                    .ref(ArrayList.class)
                                    .narrow(elementType)));
                    }
                    body._return(field);
                    applyExpose(propertyInfo.getName(false), Annotatable.from(aliasedMethod),
                            beanInclusions, outline, classInfo);
                    methods.remove(getter);
                }
                // setter
                JMethod setter = ClassHelper.findSetterInClass(implClass, publicName, fieldType);
                if (setter != null) {
                    addSetter(outline, implClass, field, setter, aliasFieldType);
                    methods.remove(setter);
                }
            }
        }

    }

    private void addXmlSeeAlso(Outline outline, Map<String, Set<String>> classesToKeep, Map<String, String> beanAliases,
            CClassInfo classInfo, JDefinedClass implClass) {
        Iterator<CClassInfo> subclasses = classInfo.listSubclasses();
        JAnnotationArrayMember arrayValue = null;
        while (subclasses.hasNext()) {
            CClassInfo subclass = subclasses.next();
            String subclassName = subclass.getName();
            if (classesToKeep.containsKey(subclassName)) {
                if (arrayValue == null) {
                    JAnnotationUse annotateXmlSeeAlso = implClass.annotate(XmlSeeAlso.class);
                    arrayValue = annotateXmlSeeAlso.paramArray("value");
                }
                String aliasClassName = beanAliases.get(subclassName);
                if (aliasClassName != null) {
                    subclassName = aliasClassName;
                }
                JDefinedClass clazz = outline.getCodeModel()
                    ._getClass(subclassName);
                arrayValue.param(clazz);
            }
        }
    }

    private void fillAliasBeanContent(Outline outline, Map<String, Set<String>> classesToKeep,
            Map<ClassOutline, JDefinedClass> beansToRename, Map<String, String> beanAliases)
            throws ClassNotFoundException, IOException {
        for (Entry<ClassOutline, JDefinedClass> beanToRenameEntry : beansToRename.entrySet()) {
            ClassOutline originalClassOutline = beanToRenameEntry.getKey();
            CClassInfo classInfo = originalClassOutline.target;
            JDefinedClass implClass = originalClassOutline.implClass;
            JDefinedClass aliasBean = beanToRenameEntry.getValue();

            // copy class content
            aliasBean.javadoc()
                .add(classInfo.javadoc);
            aliasBean._extends(implClass._extends());
            Iterator<JClass> impls = aliasBean._implements();
            while (impls.hasNext()) {
                JClass iface = impls.next();
                aliasBean._implements(iface);
            }

            Collection<JMethod> methods = implClass.methods();
            Map<String, JFieldVar> fields = implClass.fields();

            for (Entry<String, JFieldVar> entry : fields.entrySet()) {
                JFieldVar field = entry.getValue();
                String fieldName = entry.getKey();

                // TODO how can we read the value of a field?
                if ("serialVersionUID".equals(fieldName)) {
                    aliasBean.field(field.mods()
                        .getValue(), field.type(), fieldName, JExpr.lit(-1L));
                } else {
                    JType fieldType = field.type();
                    JType aliasFieldType = getAliasFieldType(outline, beanAliases, fieldType);
                    fieldType = aliasFieldType != null ? aliasFieldType : fieldType;

                    JFieldVar aliasBeanField = aliasBean.field(field.mods()
                        .getValue(), fieldType, fieldName);

                    AnnotationHelper.applyAnnotations(outline, Annotatable.from(aliasBeanField), field.annotations());

                    for (JMethod method : methods) {
                        String publicName = fieldName.substring(0, 1)
                            .toUpperCase() + fieldName.substring(1);
                        if (!method.name()
                            .endsWith(publicName)) {
                            continue;
                        }
                        JType typeOrAliasType = fieldType;
                        List<JVar> params = method.params();
                        if (params.isEmpty()) { // getter
                            method.type(typeOrAliasType);
                            aliasBean.methods()
                                .add(method);
                        } else { // setter
                            addSetter(outline, aliasBean, aliasBeanField, method, typeOrAliasType);
                        }
                    }
                }
            }

            // fix XmlSeeAlso
            Iterator<CClassInfo> subclasses = originalClassOutline.target.listSubclasses();
            JAnnotationArrayMember arrayValue = null;
            while (subclasses.hasNext()) {
                CClassInfo subclass = subclasses.next();
                String subclassAlias = beanAliases.get(subclass.fullName());
                String subclassName = subclassAlias == null ? subclass.getName() : subclassAlias;
                if (classesToKeep.containsKey(subclass.getName())) {
                    if (arrayValue == null) {
                        JAnnotationUse annotateXmlSeeAlso = aliasBean.annotate(XmlSeeAlso.class);
                        arrayValue = annotateXmlSeeAlso.paramArray("value");
                    }
                    JDefinedClass clazz = outline.getCodeModel()
                        ._getClass(subclassName);
                    arrayValue.param(clazz);
                }
            }

            Collection<JAnnotationUse> annotations = implClass.annotations();
            // XmlSeeAlso is handled by ourselves
            AnnotationHelper.applyAnnotations(outline, Annotatable.from(aliasBean), annotations, IGNORED_ANNOTATIONS);

        }
    }

    private void createAliasBeans(Outline outline, BeanInclusions beanInclusions,
            Collection<? extends ClassOutline> classOutlines, Map<ClassOutline, JDefinedClass> beansToRename,
            Map<String, String> beanAliases) throws JClassAlreadyExistsException {
        for (ClassOutline classOutline : new ArrayList<ClassOutline>(classOutlines)) { // no
                                                                                       // concurrent
                                                                                       // mod
            CClassInfo classInfo = classOutline.target;
            JDefinedClass implClass = classOutline.implClass;

            String aliasBeanName = getBeanAlias(classInfo, beanInclusions);
            if (!aliasBeanName.isEmpty()) {
                JPackage parent = implClass.getPackage();
                JDefinedClass aliasBean = addClass(outline, parent, aliasBeanName, classOutline);
                // JDefinedClass aliasBean = addClass(implClass.mods(), outline, parent,
                // aliasBeanName, implClass
                // .getClassType());
                beansToRename.put(classOutline, aliasBean); // keep for later
                beanAliases.put(classOutline.target.fullName(), aliasBean.fullName());
            }
        }
    }

    private void removeUnusedAndRenameProperties(Outline outline, BeanInclusions beanInclusions,
            Collection<? extends ClassOutline> classOutlines, Map<String, Set<String>> classesToKeep) {
        for (final ClassOutline classOutline : classOutlines) {
            CClassInfo classInfo = classOutline.target;
            String className = classInfo.getName();

            if (!classesToKeep.containsKey(className)) {
                removeClass(outline, className);
            } else {
                // remove/rename fields, setters and getters
                JDefinedClass implClass = classOutline.implClass;
                Collection<JMethod> methods = implClass.methods();
                Map<String, JFieldVar> fields = implClass.fields();
                Collection<JMethod> methodsToRemove = new ArrayList<JMethod>();
                final Set<String> propertiesToKeep = classesToKeep.get(className);
                List<CPropertyInfo> properties = classInfo.getProperties();
                List<CPropertyInfo> propertiesToRemove = new ArrayList<CPropertyInfo>();
                for (CPropertyInfo propertyInfo : properties) {
                    String propertyPrivateName = propertyInfo.getName(false); // fooBar
                    String propertyPublicName = propertyInfo.getName(true);
                    if (!propertiesToKeep.contains(propertyPrivateName)) {
                        // remove unused field and accessor methods
                        propertiesToRemove.add(propertyInfo); // no concurrent modification
                        JFieldVar fieldVar = fields.get(propertyPrivateName);
                        implClass.removeField(fieldVar);
                        for (JMethod method : methods) {
                            if (method.name()
                                .endsWith(propertyPublicName)) { // FooBar
                                methodsToRemove.add(method); // no concurrent modification
                            }
                        }
                        methods.removeAll(methodsToRemove);
                    } else {
                        // rename property alias fields and accessor methods
                        BeanInclusion beanInclusion = beanInclusions.getBeanInclusion(classInfo);
                        if (beanInclusion != null) {
                            String propertyAlias = beanInclusion.getPropertyAlias(propertyPrivateName);
                            if (propertyAlias != null) {
                                String propertyAliasPublic = propertyAlias.substring(0, 1)
                                    .toUpperCase()
                                        + propertyAlias.substring(1);
                                JFieldVar fieldVar = fields.get(propertyPrivateName);
                                fieldVar.name(propertyAlias);
                                for (JMethod method : methods) {
                                    String methodName = method.name();
                                    if (methodName.endsWith(propertyPublicName)) { // FooBar
                                        method.name(
                                                methodName.replace(propertyPublicName, propertyAliasPublic));
                                        if (methodName.startsWith("get") || methodName.startsWith("is")) {
                                            applyExpose(propertyInfo.getName(false), Annotatable.from(method),
                                                    beanInclusions, outline, classInfo);
                                        }
                                    }
                                }
                            }

                        }
                    }
                }
                properties.removeAll(propertiesToRemove);

                // remove XmlType and XmlSeeAlso
                Collection<JAnnotationUse> annotations = implClass.annotations();
                List<JAnnotationUse> annotationsToRemove = new ArrayList<JAnnotationUse>();
                for (JAnnotationUse annotation : annotations) {
                    String annotationName = annotation.getAnnotationClass()
                        .name();
                    if (annotationName.equals("XmlType") || annotationName.equals("XmlSeeAlso")) {
                        annotationsToRemove.add(annotation); // no concurrent change
                    }
                }
                for (JAnnotationUse annotationToRemove : annotationsToRemove) {
                    implClass.removeAnnotation(annotationToRemove);
                }

            }
        }
    }

    private void applyExpose(String property, Annotatable target, BeanInclusions beanInclusions,
            Outline outline, CClassInfo classInfo) {
        if (HYDRA_PRESENT) {
            BeanInclusion beanInclusion = beanInclusions.getBeanInclusion(classInfo);
            if (beanInclusion == null) {
                return;
            }
            if (beanInclusion.includesClass(classInfo.fullName())) {
                JAnnotationUse annotateExpose = target.annotate(Expose.class);
                String prefix = beanInclusion.getPrefix();
                String typeUrl = prefix.isEmpty() ? classInfo.getTypeName()
                    .getNamespaceURI() + "/" + classInfo.shortName : prefix + ":" + classInfo.shortName;

                String propertyFragment = property == null ? "" : "#" + property;

                annotateExpose.param("value", typeUrl + propertyFragment);
            }
        }

    }

    private JClass getAliasFieldType(Outline outline, Map<String, String> beanAliases, JType fieldType)
            throws ClassNotFoundException {
        JClass ret = null;
        if (fieldType instanceof JClass) {
            JClass fieldAsJClass = (JClass) fieldType;
            if (fieldAsJClass.isParameterized()) {
                List<JClass> typeParameters = fieldAsJClass.getTypeParameters();
                // getter must have one type parameter
                JClass typeParameter = typeParameters.get(0);
                String genericTypeParameterAlias = beanAliases.get(typeParameter.fullName());
                if (genericTypeParameterAlias != null) {
                    JDefinedClass aliasOfGenericTypeParameter = OutlineHelper.getJDefinedClassFromOutline(outline,
                            genericTypeParameterAlias);
                    JClass parseType = outline.getCodeModel()
                        .ref(List.class);
                    ret = parseType.narrow(aliasOfGenericTypeParameter);
                }
            } else {
                String propertyTypeAlias = beanAliases.get(fieldType.fullName());
                if (propertyTypeAlias != null) {
                    ret = OutlineHelper.getJDefinedClassFromOutline(outline, propertyTypeAlias);
                }
            }
        }
        return ret;
    }

    private void addSetter(Outline outline, JDefinedClass bean, JFieldVar fieldVar,
            JMethod originalSetter, JType fieldTypeForNewSetter) {
        if (!List.class.getName()
            .equals(fieldTypeForNewSetter.erasure()
                .fullName())) {
            JMethod aliasedMethod = bean.method(originalSetter.mods()
                .getValue(), outline.getCodeModel().VOID, originalSetter.name());
            aliasedMethod.body()
                .assign(JExpr._this()
                    .ref(fieldVar), aliasedMethod.param(fieldTypeForNewSetter, fieldVar.name()));
        }
    }

    private String getBeanAlias(CClassInfo classInfo, BeanInclusions beanInclusions) {
        BeanInclusion beanInclusionForClassInfo = beanInclusions.getBeanInclusion(classInfo);

        String aliasBeanName = "";
        if (beanInclusionForClassInfo != null) {
            aliasBeanName = beanInclusionForClassInfo.getBeanAlias();
        }
        return aliasBeanName;
    }

    private Map<String, Set<String>> getClassesToKeep(BeanInclusions beanInclusions,
            Collection<? extends ClassOutline> classOutlines) {
        Map<String, Set<String>> classesToKeep = new HashMap<String, Set<String>>();
        for (ClassOutline classOutline : classOutlines) {
            CClassInfo classInfo = classOutline.target;
            Set<String> includedPropertiesChecklist = new HashSet<String>();

            BeanInclusion beanInclusion = beanInclusions.getBeanInclusion(classInfo);
            if (beanInclusion == null) {
                continue;
            }
            // keep super classes recursively
            addClassesWithPropertiesToKeep(classesToKeep, classOutlines, classOutline, beanInclusions,
                    beanInclusion, includedPropertiesChecklist);
        }
        return classesToKeep;
    }

    private void addClassesWithPropertiesToKeep(Map<String, Set<String>> classesToKeep,
            Collection<? extends ClassOutline> classOutlines, ClassOutline classOutline,
            BeanInclusions beanInclusions, BeanInclusion beanInclusion,
            Set<String> includedPropertiesChecklist) {
        CClassInfo currentClassInfo = classOutline.target;

        // don't check BeanInclusion.includesClass here,
        // class hierarchy and property classes are automatically
        // included even if not included by any beanInclusion

        do { // keep class hierarchy
            String currentClassName = currentClassInfo.getName();
            if (!classesToKeep.containsKey(currentClassName)) {
                classesToKeep.put(currentClassName, new HashSet<String>());
            }

            List<CPropertyInfo> propertyInfos = currentClassInfo.getProperties();

            for (CPropertyInfo propertyInfo : propertyInfos) {
                String propertyInfoName = propertyInfo.getName(false); // fooBar

                if (beanInclusion.includesProperty(propertyInfoName)) {
                    Set<String> props = classesToKeep.get(currentClassName);
                    props.add(propertyInfoName);
                    includedPropertiesChecklist.add(propertyInfoName);

                    // include property types
                    String propertyTypeToKeep = findPropertyTypeToKeep(classOutline, propertyInfo);
                    if (propertyTypeToKeep != null) {
                        for (ClassOutline propertyClass : classOutlines) {
                            if (propertyTypeToKeep.equals(propertyClass.target.fullName())) {
                                // include property type class hierarchy
                                addClassesWithPropertiesToKeep(classesToKeep, classOutlines, propertyClass,
                                        beanInclusions, beanInclusion, includedPropertiesChecklist);
                                break;
                            }

                        }
                    }
                }
            }
        } while (null != (currentClassInfo = currentClassInfo.getBaseClass()));
    }

    private String findPropertyTypeToKeep(ClassOutline classOutline, CPropertyInfo propertyInfo) {
        Collection<JMethod> methods = classOutline.implClass.methods();
        for (JMethod jMethod : methods) {
            if (jMethod.name()
                .endsWith(propertyInfo.getName(true))) {
                JType propertyType = jMethod.type();
                String propertyTypeToKeep = propertyType.fullName();
                if (propertyType instanceof JClass) {
                    JClass propertyJClass = (JClass) propertyType;
                    if (propertyJClass.isParameterized()) {
                        List<JClass> typeParams = propertyJClass.getTypeParameters();
                        for (JClass typeParam : typeParams) {
                            propertyTypeToKeep = typeParam.fullName();
                            break;
                        }
                    } else {
                        propertyTypeToKeep = propertyJClass.fullName();
                    }
                }
                return propertyTypeToKeep;
            }
        }
        return findPropertyTypeToKeep(classOutline.getSuperClass(), propertyInfo);
    }

    private ClassOutline findMatchingInclusionEntry(Collection<? extends ClassOutline> classOutlines,
            BeanInclusion inclusionEntry) {
        for (ClassOutline classOutline : classOutlines) {
            String className = classOutline.target.getName();
            if (inclusionEntry.includesClass(className)) {
                return classOutline;
            }
        }
        return null;
    }

    private void removeClass(Outline outline, String foundClass) {
        JDefinedClass clazz = OutlineHelper.getJDefinedClassFromOutline(outline, foundClass);
        clazz._package()
            .remove(clazz);

        // Zap createXXX method from ObjectFactory
        String removedClassName = clazz.fullName();
        String factoryName = clazz._package()
            .name() + ".ObjectFactory";
        JDefinedClass objFactory = OutlineHelper.getJDefinedClassFromOutline(outline, factoryName);
        if (objFactory != null) {
            Collection<JMethod> methods = objFactory.methods();
            Iterator<JMethod> methodIterator = methods.iterator();
            List<JMethod> methodsToRemove = new ArrayList<JMethod>();
            while (methodIterator.hasNext()) {
                JMethod method = methodIterator.next();
                String toRemoveCandidate = method.type()
                    .fullName();
                if (toRemoveCandidate.equals(removedClassName)
                        || toRemoveCandidate.equals("javax.xml.bind.JAXBElement<" + removedClassName + ">")
                        || hasXmlElementDeclScope(method, removedClassName)) {
                    methodsToRemove.add(method);
                }
            }
            methods.removeAll(methodsToRemove);
            if (methods.isEmpty()) {
                clazz._package()
                    .remove(objFactory);

                // delete the entire package, if empty
                if (!clazz._package()
                    .classes()
                    .hasNext()) {
                    Iterator<JPackage> pkgs = clazz._package()
                        .owner()
                        .packages();
                    while (pkgs.hasNext()) {
                        JPackage jPackage = (JPackage) pkgs.next();
                        if (jPackage.name()
                            .equals(clazz.getPackage()
                                .name())) {
                            pkgs.remove();
                        }
                    }
                }
            }
        }
    }

    private JDefinedClass addClass(Outline outline, JPackage targetPackage, String className, ClassOutline replaces) {

        CClassInfo classInfo = replaces.target;
        Locator locator = classInfo.getLocator();
        QName typeName = classInfo.getTypeName();
        QName elementName = classInfo.getElementName();
        XSComponent source = classInfo.getSchemaComponent();
        CCustomizations customizations = classInfo.getCustomizations();
        // Ring ring = Ring.begin();
        // CClassInfo myClassInfo = new CClassInfo(classInfo.model,targetPackage, className,
        // locator, typeName, elementName, source, customizations);
        // ring.end(ring);
        CClassInfo newClassInfo = new CClassInfo(classInfo.model, targetPackage.owner(), targetPackage.name()
            .isEmpty() ? className : targetPackage.name() + "." + className, locator,
                typeName, elementName, source, customizations);
        // CClassInfo newClassInfo = new CClassInfo(classInfo.model, targetPackage.owner(),
        // className, locator,
        // typeName, elementName, source, customizations);
        // also adds classInfo to outline:
        ClassOutline classOutline = outline.getClazz(newClassInfo);
        JDefinedClass aliasBean = classOutline.implClass;
        String factoryName = aliasBean._package()
            .name() + ".ObjectFactory";
        JDefinedClass objFactory = OutlineHelper.getJDefinedClassFromOutline(outline, factoryName);
        JMethod factoryMethod = objFactory.method(1, aliasBean, "create" + aliasBean.name());
        factoryMethod.body()
            ._return(JExpr._new(aliasBean));

        return aliasBean;
    }

    private boolean hasXmlElementDeclScope(JMethod method, String removedClassName) {
        try {
            Collection<JAnnotationUse> annotations = method.annotations();
            for (JAnnotationUse ann : annotations) {
                if ("javax.xml.bind.annotation.XmlElementDecl".equals(ann.getAnnotationClass()
                    .fullName())) {
                    Map<String, JAnnotationValue> annotationMembers = ann.getAnnotationMembers();
                    JAnnotationValue scopeAnn = annotationMembers.get("scope");
                    if (scopeAnn != null) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        OutputStreamWriter writer = new OutputStreamWriter(out);
                        scopeAnn.generate(new JFormatter(writer));
                        writer.flush();
                        String annotationCode = new String(out.toByteArray(), "us-ascii");
                        if ((removedClassName + ".class").equals(annotationCode)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Exception e) {
            throw new RuntimeException("failed to determine scope annotation value", e);
        }
    }

    private BeanInclusions getBeanInclusions(CCustomizations ccs) {
        Map<String, List<BeanInclusion>> includedClasses = new HashMap<String, List<BeanInclusion>>();
        for (CPluginCustomization pluginCustomization : findMyCustomizations(ccs, "include")) {
            pluginCustomization.markAsAcknowledged();
            Element includeElement = pluginCustomization.element;
            HashMap<String, String> aliases = new HashMap<String, String>();
            String packageRoot = includeElement.getAttribute("packageRoot");
            String prefix = includeElement.getAttribute("prefix");
            String bean = includeElement.getAttribute("bean");
            if (bean == null || bean.isEmpty()) {
                NodeList childNodes = includeElement.getChildNodes();
                for (int i = 0; i < childNodes.getLength(); i++) {
                    Node beanNode = childNodes.item(i);
                    if ("bean".equals(beanNode.getLocalName()) && beanNode instanceof Element) {
                        Element beanElement = (Element) beanNode;
                        HashSet<String> propertiesToInclude = new HashSet<String>();
                        collectPropertiesAndAliases(beanElement, propertiesToInclude, aliases);
                        bean = beanElement.getAttribute("name");
                        BeanInclusion beanInclusion = new BeanInclusion(bean, propertiesToInclude, aliases,
                                packageRoot, prefix);
                        beanInclusion.setBeanAlias(beanElement.getAttribute("alias"));
                        addBeanInclusion(includedClasses, beanInclusion);
                    }
                }
            } else {
                HashSet<String> propertiesToInclude = new HashSet<String>();
                collectPropertiesAndAliases(includeElement, propertiesToInclude, aliases);
                BeanInclusion beanInclusion = new BeanInclusion(bean, propertiesToInclude, aliases, packageRoot,
                        prefix);
                beanInclusion.setBeanAlias(includeElement.getAttribute("alias"));
                addBeanInclusion(includedClasses, beanInclusion);
            }

        }
        return new BeanInclusions(includedClasses);
    }

    private void addBeanInclusion(Map<String, List<BeanInclusion>> includedClasses, BeanInclusion beanInclusion) {
        List<BeanInclusion> list = includedClasses.get(beanInclusion.simpleName);
        if (list == null) {
            list = new ArrayList<BeanInclusion>();
            includedClasses.put(beanInclusion.simpleName, list);
        }
        list.add(beanInclusion);
    }

    private void collectPropertiesAndAliases(Element includeElement, HashSet<String> propertiesToInclude,
            HashMap<String, String> aliases) {
        NodeList childNodes = includeElement.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node item = childNodes.item(i);
            if ("alias".equals(item.getLocalName())) {
                String aliasName = item.getTextContent();
                NamedNodeMap attributes = item.getAttributes();
                Node propertyAttr = attributes.getNamedItem("property");
                String propertyName = propertyAttr.getNodeValue();
                propertiesToInclude.add(propertyName);
                aliases.put(propertyName, aliasName);
            }
        }
        String properties = includeElement.getAttribute("properties");
        if (!properties.trim()
            .isEmpty()) {
            Collections.addAll(propertiesToInclude, properties.split(" "));
        }
    }

    @SuppressWarnings("unused")
    private void dump(CCustomizations cc) {
        for (int i = 0; i < cc.size(); i++) {
            Node n = cc.get(i).element;
            System.err.println("\t" + n.getNodeName() + " " + n.getNodeValue());
            NamedNodeMap attribs = n.getAttributes();
            if (attribs != null) {
                for (int j = 0; j < attribs.getLength(); j++) {
                    Node attrib = attribs.item(j);
                    System.err.println("\t\t" + attrib);
                }
            }
        }
    }

    private CCustomizations findMyCustomizations(CCustomizations cc, String name) {
        CCustomizations list = new CCustomizations();
        for (CPluginCustomization cpc : cc) {
            Element e = cpc.element;
            if (NAMESPACE_URI.equals(e.getNamespaceURI()) && e.getLocalName()
                .equals(name)) {
                list.add(cpc);
            }
        }

        return list;

    }
}