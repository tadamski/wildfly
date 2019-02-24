package org.jboss.as.ejb3.aop;

import org.jboss.metadata.ejb.parser.jboss.ejb3.AbstractEJBBoundMetaDataParser;
import org.jboss.metadata.property.PropertyReplacer;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public class AOPParser extends AbstractEJBBoundMetaDataParser<AOPMetaData> {
    public static final String NAMESPACE_URI = "urn:aop:1.0";
    public static final AOPParser INSTANCE = new AOPParser();

    @Override
    public AOPMetaData parse(XMLStreamReader reader, PropertyReplacer propertyReplacer) throws XMLStreamException {
        AOPMetaData metaData = new AOPMetaData();
        processElements(metaData, reader, propertyReplacer);
        return metaData;
    }

    @Override
    protected void processElement(AOPMetaData metaData, XMLStreamReader reader, final PropertyReplacer propertyReplacer) throws XMLStreamException {
        if (reader.getNamespaceURI().equals(NAMESPACE_URI)) {
            final String localName = reader.getLocalName();
            if (localName.equals("interceptor-class")) {
                metaData.setInterceptorClass(getElementText(reader, propertyReplacer));
            } else if (localName.equals("interceptor-module")) {
                metaData.setInterceptorModule(getElementText(reader, propertyReplacer));
            } else {
                throw unexpectedElement(reader);
            }
        } else {
            super.processElement(metaData, reader, propertyReplacer);
        }
    }
}
