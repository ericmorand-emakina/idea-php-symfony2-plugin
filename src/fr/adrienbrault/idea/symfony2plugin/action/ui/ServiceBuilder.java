package fr.adrienbrault.idea.symfony2plugin.action.ui;

import com.intellij.openapi.project.Project;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import fr.adrienbrault.idea.symfony2plugin.dic.ContainerParameter;
import fr.adrienbrault.idea.symfony2plugin.stubs.ContainerCollectionResolver;
import fr.adrienbrault.idea.symfony2plugin.util.PhpElementsUtil;
import fr.adrienbrault.idea.symfony2plugin.util.dict.ServiceTag;
import fr.adrienbrault.idea.symfony2plugin.util.dict.ServiceUtil;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

public class ServiceBuilder {

    public enum OutputType {
        Yaml, XML,
    }

    private List<MethodParameter.MethodModelParameter>  methodModelParameter;
    private Project project;

    public ServiceBuilder(List<MethodParameter.MethodModelParameter> methodModelParameter, Project project) {
        this.methodModelParameter = methodModelParameter;
        this.project = project;
    }

    @Nullable
    public String build(OutputType outputType, String className, String serviceName) {
        HashMap<String, ArrayList<MethodParameter.MethodModelParameter>> methods = new HashMap<String, ArrayList<MethodParameter.MethodModelParameter>>();

        for(MethodParameter.MethodModelParameter methodModelParameter: this.methodModelParameter) {

            String methodName = methodModelParameter.getName();
            if(methodModelParameter.getMethod().getMethodType(false) == Method.MethodType.CONSTRUCTOR) {
                methodName = "__construct";
            }

            if(methods.containsKey(methodModelParameter.getName())) {
                methods.get(methodName).add(methodModelParameter);
            } else {
                methods.put(methodName, new ArrayList<MethodParameter.MethodModelParameter>(Arrays.asList(methodModelParameter)));
            }
        }

        if(outputType == OutputType.Yaml) {
            return buildYaml(methods, className, serviceName);
        }

        if(outputType == OutputType.XML) {
            return buildXml(methods, className, serviceName);
        }

        return null;
    }

    @Nullable
    private List<String> getParameters(List<MethodParameter.MethodModelParameter> methodModelParameters) {
        boolean hasCall = false;
        ArrayList<String> methodCalls = new ArrayList<String>();

        // sort by indexes parameter
        Collections.sort(methodModelParameters, new Comparator<MethodParameter.MethodModelParameter>() {
            @Override
            public int compare(MethodParameter.MethodModelParameter o1, MethodParameter.MethodModelParameter o2) {
                return ((Integer) o1.getIndex()).compareTo(o2.getIndex());
            }
        });

        for(MethodParameter.MethodModelParameter methodModelParameter: methodModelParameters) {

            // only add items which have at least one service parameter
            if(!hasCall && methodModelParameter.isPossibleService()) {
                hasCall = true;
            }

            // missing required parameter; add to service template, so use can correct it after
            String currentService = methodModelParameter.getCurrentService();
            if(currentService == null || !methodModelParameter.isPossibleService()) {
                currentService = "?";
            }

            methodCalls.add(currentService);

        }

        if(!hasCall || methodCalls.size() == 0) {
            return null;
        }

        return methodCalls;
    }

    @Nullable
    private String getClassAsParameter(String className) {

        if(className.startsWith("\\")) {
            className = className.substring(1);
        }

        for(Map.Entry<String, ContainerParameter> entry: ContainerCollectionResolver.getParameters(this.project).entrySet()) {
            String parameterValue = entry.getValue().getValue();
            if(parameterValue != null) {
                if(parameterValue.startsWith("\\")) {
                    parameterValue = parameterValue.substring(1);
                }

                if(parameterValue.equals(className)) {
                    return entry.getKey();
                }

            }

        }

        return null;

    }

    @Nullable
    private String buildXml(Map<String, ArrayList<MethodParameter.MethodModelParameter>> methods, String className, String serviceName) {

        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder = null;
        try {
            docBuilder = docFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            return null;
        }


        // root elements
        final Document doc = docBuilder.newDocument();
        final Element rootElement = doc.createElement("service");
        rootElement.setAttribute("id", serviceName);

        String classAsParameter = getClassAsParameter(className);

        rootElement.setAttribute("class", classAsParameter != null ? classAsParameter : className);
        doc.appendChild(rootElement);

        if(methods.containsKey("__construct")) {

            List<String> parameters = getParameters(methods.get("__construct"));
            if(parameters != null) {
                for(String parameter: parameters) {
                    Element argument = doc.createElement("argument");
                    argument.setAttribute("id", parameter);
                    argument.setAttribute("type", "service");
                    rootElement.appendChild(argument);
                }
            }

            methods.remove("__construct");
        }


        for(Map.Entry<String, ArrayList<MethodParameter.MethodModelParameter>> entry: methods.entrySet()) {

            List<String> parameters = getParameters(entry.getValue());
            if(parameters != null) {
                Element calls = doc.createElement("call");
                calls.setAttribute("method", entry.getKey());

                for(String parameter: parameters) {
                    Element argument = doc.createElement("argument");
                    argument.setAttribute("id", parameter);
                    argument.setAttribute("type", "service");
                    calls.appendChild(argument);
                }

                rootElement.appendChild(calls);
            }
        }

        serviceTagCallback(className, new TagCallbackInterface() {
            @Override
            public void onTags(@NotNull List<ServiceTag> serviceTags) {

                for (ServiceTag serviceTag : serviceTags) {
                    try {
                        // convert string to node
                        Element node = DocumentBuilderFactory
                            .newInstance()
                            .newDocumentBuilder()
                            .parse(new ByteArrayInputStream(serviceTag.toXmlString().getBytes()))
                            .getDocumentElement();

                        rootElement.appendChild(doc.importNode(node, true));

                    } catch (SAXException ignored) {
                    } catch (IOException ignored) {
                    } catch (ParserConfigurationException ignored) {
                    }
                }
            }

        });


        try {
            return getStringFromDocument(doc);
        } catch (TransformerException e) {
            return null;
        }
    }

    private static String getStringFromDocument(Document doc) throws TransformerException {

        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        transformer.transform(new DOMSource(doc), result);

        return writer.toString();
    }


    private String buildYaml(Map<String, ArrayList<MethodParameter.MethodModelParameter>> methods, String className, String serviceName) {

        final String indent = "\t";
        final List<String> lines = new ArrayList<String>();

        String classAsParameter = getClassAsParameter(className);

        lines.add(serviceName + ":");
        lines.add(indent + "class: " + (classAsParameter != null ? "'%" + classAsParameter + "%'" : className));

        if(methods.containsKey("__construct")) {

            List<String> parameters = getParameters(methods.get("__construct"));
            if(parameters != null) {
                lines.add(String.format("%sarguments: [%s]", indent, StringUtils.join(formatYamlService(parameters), ", ")));
            }

            methods.remove("__construct");
        }

        List<String> calls = new ArrayList<String>();
        for(Map.Entry<String, ArrayList<MethodParameter.MethodModelParameter>> entry: methods.entrySet()) {
            List<String> parameters = getParameters(entry.getValue());
            if(parameters != null) {
                calls.add(String.format("%s%s- [%s, [%s]]", indent, indent, entry.getKey(), StringUtils.join(formatYamlService(parameters), ", ")));
            }
        }

        if(calls.size() > 0) {
            lines.add(indent + "calls:");
            lines.addAll(calls);
        }

        serviceTagCallback(className, new TagCallbackInterface() {
            @Override
            public void onTags(@NotNull List<ServiceTag> serviceTags) {
                lines.add(indent + "tags:");
                for (ServiceTag serviceTag : serviceTags) {
                    lines.add(indent + indent + serviceTag.toYamlString());
                }
            }
        });

        return StringUtils.join(lines, "\n");
    }

    private void serviceTagCallback(String className, TagCallbackInterface callback) {

        PhpClass phpClass = PhpElementsUtil.getClass(project, className);
        if(phpClass == null) {
            return;
        }

        List<ServiceTag> serviceTags = new ArrayList<ServiceTag>();
        for (String tag : ServiceUtil.getPhpClassServiceTags(phpClass)) {
            ServiceTag serviceTag = new ServiceTag(phpClass, tag);
            ServiceUtil.decorateServiceTag(serviceTag);
            serviceTags.add(serviceTag);
        }

        if(serviceTags.size() == 0) {
            return;
        }

        callback.onTags(serviceTags);
    }

    private List<String> formatYamlService(List<String> parameters) {

        // append yaml syntax, more will follow...
        List<String> yamlSyntaxParameters = new ArrayList<String>();
        for(String parameter: parameters) {
            yamlSyntaxParameters.add(String.format("'@%s'", parameter));
        }

        return yamlSyntaxParameters;
    }

    public interface TagCallbackInterface {
        void onTags(@NotNull List<ServiceTag> tags);
    }

}
