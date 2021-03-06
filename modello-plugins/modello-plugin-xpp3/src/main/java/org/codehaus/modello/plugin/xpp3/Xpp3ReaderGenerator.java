package org.codehaus.modello.plugin.xpp3;

/*
 * Copyright (c) 2004, Codehaus.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import org.codehaus.modello.ModelloException;
import org.codehaus.modello.model.Model;
import org.codehaus.modello.model.ModelAssociation;
import org.codehaus.modello.model.ModelClass;
import org.codehaus.modello.model.ModelDefault;
import org.codehaus.modello.model.ModelField;
import org.codehaus.modello.plugin.java.javasource.JClass;
import org.codehaus.modello.plugin.java.javasource.JField;
import org.codehaus.modello.plugin.java.javasource.JMethod;
import org.codehaus.modello.plugin.java.javasource.JParameter;
import org.codehaus.modello.plugin.java.javasource.JSourceCode;
import org.codehaus.modello.plugin.java.javasource.JSourceWriter;
import org.codehaus.modello.plugin.java.javasource.JType;
import org.codehaus.modello.plugin.java.metadata.JavaClassMetadata;
import org.codehaus.modello.plugin.model.ModelClassMetadata;
import org.codehaus.modello.plugins.xml.metadata.XmlAssociationMetadata;
import org.codehaus.modello.plugins.xml.metadata.XmlClassMetadata;
import org.codehaus.modello.plugins.xml.metadata.XmlFieldMetadata;
import org.codehaus.plexus.util.StringUtils;

/**
 * @author <a href="mailto:jason@modello.org">Jason van Zyl</a>
 * @author <a href="mailto:evenisse@codehaus.org">Emmanuel Venisse</a>
 * @version $Id$
 */
public class Xpp3ReaderGenerator
    extends AbstractXpp3Generator
{

    private static final String SOURCE_PARAM = "source";

    private static final String LOCATION_VAR = "_location";

    private ModelClass locationTracker;

    private String locationField;

    private ModelClass sourceTracker;

    private String trackingArgs;

    protected boolean isLocationTracking()
    {
        return false;
    }

    public void generate( Model model, Properties parameters )
        throws ModelloException
    {
        initialize( model, parameters );

        locationTracker = sourceTracker = null;
        trackingArgs = locationField = "";

        if ( isLocationTracking() )
        {
            locationTracker = model.getLocationTracker( getGeneratedVersion() );
            if ( locationTracker == null )
            {
                throw new ModelloException( "No model class has been marked as location tracker"
                    + " via the attribute locationTracker=\"locations\"" + ", cannot generate extended reader." );
            }

            locationField =
                ( (ModelClassMetadata) locationTracker.getMetadata( ModelClassMetadata.ID ) ).getLocationTracker();

            sourceTracker = model.getSourceTracker( getGeneratedVersion() );

            if ( sourceTracker != null )
            {
                trackingArgs += ", " + SOURCE_PARAM;
            }
        }

        try
        {
            generateXpp3Reader();
        }
        catch ( IOException ex )
        {
            throw new ModelloException( "Exception while generating XPP3 Reader.", ex );
        }
    }

    private void writeAllClassesReaders( Model objectModel, JClass jClass )
    {
        ModelClass root = objectModel.getClass( objectModel.getRoot( getGeneratedVersion() ), getGeneratedVersion() );

        for ( ModelClass clazz : getClasses( objectModel ) )
        {
            if ( isTrackingSupport( clazz ) )
            {
                continue;
            }

            writeClassReaders( clazz, jClass, root.getName().equals( clazz.getName() ) );
        }
    }

    private void writeClassReaders( ModelClass modelClass, JClass jClass, boolean rootElement )
    {
        JavaClassMetadata javaClassMetadata =
            (JavaClassMetadata) modelClass.getMetadata( JavaClassMetadata.class.getName() );

        // Skip abstract classes, no way to parse them out into objects
        if ( javaClassMetadata.isAbstract() )
        {
            return;
        }

        XmlClassMetadata xmlClassMetadata = (XmlClassMetadata) modelClass.getMetadata( XmlClassMetadata.ID );
        if ( !rootElement && !xmlClassMetadata.isStandaloneRead() )
        {
            return;
        }

        String className = modelClass.getName();

        String capClassName = capitalise( className );

        String readerMethodName = "read";
        if ( !rootElement )
        {
            readerMethodName += capClassName;
        }

        // ----------------------------------------------------------------------
        // Write the read(XmlPullParser,boolean) method which will do the unmarshalling.
        // ----------------------------------------------------------------------

        JMethod unmarshall = new JMethod( readerMethodName, new JClass( className ), null );
        unmarshall.getModifiers().makePrivate();

        unmarshall.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );
        unmarshall.addParameter( new JParameter( JClass.BOOLEAN, "strict" ) );
        addTrackingParameters( unmarshall );

        unmarshall.addException( new JClass( "IOException" ) );
        unmarshall.addException( new JClass( "XmlPullParserException" ) );

        JSourceCode sc = unmarshall.getSourceCode();

        String tagName = resolveTagName( modelClass );
        String variableName = uncapitalise( className );

        sc.add( "int eventType = parser.getEventType();" );

        sc.add( "while ( eventType != XmlPullParser.END_DOCUMENT )" );

        sc.add( "{" );
        sc.indent();

        sc.add( "if ( eventType == XmlPullParser.START_TAG )" );

        sc.add( "{" );
        sc.indent();

        sc.add( "if ( strict && ! \"" + tagName + "\".equals( parser.getName() ) )" );

        sc.add( "{" );
        sc.addIndented( "throw new XmlPullParserException( \"Expected root element '" + tagName + "' but "
                        + "found '\" + parser.getName() + \"'\", parser, null );" );
        sc.add( "}" );

        sc.add( className + ' ' + variableName + " = parse" + capClassName + "( parser, strict" + trackingArgs
            + " );" );

        if ( rootElement )
        {
            sc.add( variableName + ".setModelEncoding( parser.getInputEncoding() );" );
        }

        sc.add( "return " + variableName + ';' );

        sc.unindent();
        sc.add( "}" );

        sc.add( "eventType = parser.next();" );

        sc.unindent();
        sc.add( "}" );

        sc.add( "throw new XmlPullParserException( \"Expected root element '" + tagName + "' but "
                        + "found no element at all: invalid XML document\", parser, null );" );

        jClass.addMethod( unmarshall );

        // ----------------------------------------------------------------------
        // Write the read(Reader[,boolean]) methods which will do the unmarshalling.
        // ----------------------------------------------------------------------

        unmarshall = new JMethod( readerMethodName, new JClass( className ), null );
        unmarshall.setComment( "@see ReaderFactory#newXmlReader" );

        unmarshall.addParameter( new JParameter( new JClass( "Reader" ), "reader" ) );
        unmarshall.addParameter( new JParameter( JClass.BOOLEAN, "strict" ) );
        addTrackingParameters( unmarshall );

        unmarshall.addException( new JClass( "IOException" ) );
        unmarshall.addException( new JClass( "XmlPullParserException" ) );

        sc = unmarshall.getSourceCode();

        sc.add( "XmlPullParser parser = new MXParser();" );

        sc.add( "" );

        sc.add( "parser.setInput( reader );" );

        sc.add( "" );

        sc.add( "initParser( parser );" );

        sc.add( "" );

        sc.add( "return " + readerMethodName + "( parser, strict" + trackingArgs + " );" );

        jClass.addMethod( unmarshall );

        // ----------------------------------------------------------------------

        if ( locationTracker == null )
        {
            unmarshall = new JMethod( readerMethodName, new JClass( className ), null );
            unmarshall.setComment( "@see ReaderFactory#newXmlReader" );

            unmarshall.addParameter( new JParameter( new JClass( "Reader" ), "reader" ) );

            unmarshall.addException( new JClass( "IOException" ) );
            unmarshall.addException( new JClass( "XmlPullParserException" ) );

            sc = unmarshall.getSourceCode();
            sc.add( "return " + readerMethodName + "( reader, true );" );

            jClass.addMethod( unmarshall );
        }

        // ----------------------------------------------------------------------
        // Write the read(InputStream[,boolean]) methods which will do the unmarshalling.
        // ----------------------------------------------------------------------

        unmarshall = new JMethod( readerMethodName, new JClass( className ), null );

        unmarshall.addParameter( new JParameter( new JClass( "InputStream" ), "in" ) );
        unmarshall.addParameter( new JParameter( JClass.BOOLEAN, "strict" ) );
        addTrackingParameters( unmarshall );

        unmarshall.addException( new JClass( "IOException" ) );
        unmarshall.addException( new JClass( "XmlPullParserException" ) );

        sc = unmarshall.getSourceCode();

        sc.add( "return " + readerMethodName + "( ReaderFactory.newXmlReader( in ), strict" + trackingArgs + " );" );

        jClass.addMethod( unmarshall );

        // --------------------------------------------------------------------

        if ( locationTracker == null )
        {
            unmarshall = new JMethod( readerMethodName, new JClass( className ), null );

            unmarshall.addParameter( new JParameter( new JClass( "InputStream" ), "in" ) );

            unmarshall.addException( new JClass( "IOException" ) );
            unmarshall.addException( new JClass( "XmlPullParserException" ) );

            sc = unmarshall.getSourceCode();

            sc.add( "return " + readerMethodName + "( ReaderFactory.newXmlReader( in ) );" );

            jClass.addMethod( unmarshall );
        }
    }

    private void generateXpp3Reader()
        throws ModelloException, IOException
    {
        Model objectModel = getModel();

        String packageName = objectModel.getDefaultPackageName( isPackageWithVersion(), getGeneratedVersion() )
            + ".io.xpp3";

        String unmarshallerName = getFileName( "Xpp3Reader" + ( isLocationTracking() ? "Ex" : "" ) );

        JSourceWriter sourceWriter = newJSourceWriter( packageName, unmarshallerName );

        JClass jClass = new JClass( packageName + '.' + unmarshallerName );
        initHeader( jClass );
        suppressAllWarnings( objectModel, jClass );

        jClass.addImport( "org.codehaus.plexus.util.ReaderFactory" );
        jClass.addImport( "org.codehaus.plexus.util.xml.pull.MXParser" );
        jClass.addImport( "org.codehaus.plexus.util.xml.pull.XmlPullParser" );
        jClass.addImport( "org.codehaus.plexus.util.xml.pull.XmlPullParserException" );
        jClass.addImport( "java.io.InputStream" );
        jClass.addImport( "java.io.IOException" );
        jClass.addImport( "java.io.Reader" );
        jClass.addImport( "java.text.DateFormat" );
        jClass.addImport( "java.util.Locale" );

        addModelImports( jClass, null );

        // ----------------------------------------------------------------------
        // Write option setters
        // ----------------------------------------------------------------------

        // The Field
        JField addDefaultEntities = new JField( JType.BOOLEAN, "addDefaultEntities" );

        addDefaultEntities.setComment(
            "If set the parser will be loaded with all single characters from the XHTML specification.\n" +
                "The entities used:\n" + "<ul>\n" + "<li>http://www.w3.org/TR/xhtml1/DTD/xhtml-lat1.ent</li>\n" +
                "<li>http://www.w3.org/TR/xhtml1/DTD/xhtml-special.ent</li>\n" +
                "<li>http://www.w3.org/TR/xhtml1/DTD/xhtml-symbol.ent</li>\n" + "</ul>\n" );

        addDefaultEntities.setInitString( "true" );

        jClass.addField( addDefaultEntities );

        // The setter
        JMethod addDefaultEntitiesSetter = new JMethod( "setAddDefaultEntities" );

        addDefaultEntitiesSetter.addParameter( new JParameter( JType.BOOLEAN, "addDefaultEntities" ) );

        addDefaultEntitiesSetter.setSourceCode( "this.addDefaultEntities = addDefaultEntities;" );

        addDefaultEntitiesSetter.setComment( "Sets the state of the \"add default entities\" flag." );

        jClass.addMethod( addDefaultEntitiesSetter );

        // The getter
        JMethod addDefaultEntitiesGetter = new JMethod( "getAddDefaultEntities", JType.BOOLEAN, null );

        addDefaultEntitiesGetter.setComment( "Returns the state of the \"add default entities\" flag." );

        addDefaultEntitiesGetter.setSourceCode( "return addDefaultEntities;" );

        jClass.addMethod( addDefaultEntitiesGetter );

        // ----------------------------------------------------------------------
        // Write the class readers
        // ----------------------------------------------------------------------

        writeAllClassesReaders( objectModel, jClass );

        // ----------------------------------------------------------------------
        // Write the class parsers
        // ----------------------------------------------------------------------

        writeAllClassesParser( objectModel, jClass );

        // ----------------------------------------------------------------------
        // Write helpers
        // ----------------------------------------------------------------------

        writeHelpers( jClass );

        // ----------------------------------------------------------------------
        //
        // ----------------------------------------------------------------------

        jClass.print( sourceWriter );

        sourceWriter.close();
    }

    private void writeAllClassesParser( Model objectModel, JClass jClass )
    {
        ModelClass root = objectModel.getClass( objectModel.getRoot( getGeneratedVersion() ), getGeneratedVersion() );

        for ( ModelClass clazz : getClasses( objectModel ) )
        {
            if ( isTrackingSupport( clazz ) )
            {
                continue;
            }

            writeClassParser( clazz, jClass, root.getName().equals( clazz.getName() ) );
        }
    }

    private void writeClassParser( ModelClass modelClass, JClass jClass, boolean rootElement )
    {
        JavaClassMetadata javaClassMetadata =
            (JavaClassMetadata) modelClass.getMetadata( JavaClassMetadata.class.getName() );

        // Skip abstract classes, no way to parse them out into objects
        if ( javaClassMetadata.isAbstract() )
        {
            return;
        }

        String className = modelClass.getName();

        String capClassName = capitalise( className );

        String uncapClassName = uncapitalise( className );

        JMethod unmarshall = new JMethod( "parse" + capClassName, new JClass( className ), null );
        unmarshall.getModifiers().makePrivate();

        unmarshall.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );
        unmarshall.addParameter( new JParameter( JClass.BOOLEAN, "strict" ) );
        addTrackingParameters( unmarshall );

        unmarshall.addException( new JClass( "IOException" ) );
        unmarshall.addException( new JClass( "XmlPullParserException" ) );

        JSourceCode sc = unmarshall.getSourceCode();

        sc.add( "String tagName = parser.getName();" );
        sc.add( className + " " + uncapClassName + " = new " + className + "();" );

        if ( locationTracker != null )
        {
            sc.add( locationTracker.getName() + " " + LOCATION_VAR + ";" );
            writeNewSetLocation( "\"\"", uncapClassName, null, sc );
        }

        ModelField contentField = null;

        List<ModelField> modelFields = getFieldsForXml( modelClass, getGeneratedVersion() );

        // read all XML attributes first
        contentField = writeClassAttributesParser( modelFields, uncapClassName, rootElement, sc, jClass );

        // then read content, either content field or elements
        if ( contentField != null )
        {
            writePrimitiveField( contentField, contentField.getType(), uncapClassName, uncapClassName, "\"\"", "set"
                + capitalise( contentField.getName() ), sc, jClass );
        }
        else
        {
            //Write other fields

            sc.add( "java.util.Set parsed = new java.util.HashSet();" );

            sc.add( "while ( ( strict ? parser.nextTag() : nextTag( parser ) ) == XmlPullParser.START_TAG )" );

            sc.add( "{" );
            sc.indent();

            boolean addElse = false;

            for ( ModelField field : modelFields )
            {
                XmlFieldMetadata xmlFieldMetadata = (XmlFieldMetadata) field.getMetadata( XmlFieldMetadata.ID );

                if ( !xmlFieldMetadata.isAttribute() )
                {
                    processField( field, xmlFieldMetadata, addElse, sc, uncapClassName, jClass );

                    addElse = true;
                }
            }

            if ( addElse )
            {
                sc.add( "else" );

                sc.add( "{" );
                sc.indent();
            }

            sc.add( "checkUnknownElement( parser, strict );" );

            if ( addElse )
            {
                sc.unindent();
                sc.add( "}" );
            }

            sc.unindent();
            sc.add( "}" );
        }

        sc.add( "return " + uncapClassName + ";" );

        jClass.addMethod( unmarshall );
    }

    private ModelField writeClassAttributesParser( List<ModelField> modelFields, String objectName,
                                                   boolean rootElement, JSourceCode sc, JClass jClass )
    {
        ModelField contentField = null;

        sc.add( "for ( int i = parser.getAttributeCount() - 1; i >= 0; i-- )" );
        sc.add( "{" );
        sc.indent();
        sc.add( "String name = parser.getAttributeName( i );" );
        sc.add( "String value = parser.getAttributeValue( i );" );
        sc.add( "" );

        sc.add( "if ( name.indexOf( ':' ) >= 0 )" );
        sc.add( "{" );
        sc.addIndented( "// just ignore attributes with non-default namespace (for example: xmlns:xsi)" );
        sc.add( "}" );
        if ( rootElement )
        {
            sc.add( "else if ( \"xmlns\".equals( name ) )" );
            sc.add( "{" );
            sc.addIndented( "// ignore xmlns attribute in root class, which is a reserved attribute name" );
            sc.add( "}" );
        }

        for ( ModelField field : modelFields )
        {
            XmlFieldMetadata xmlFieldMetadata = (XmlFieldMetadata) field.getMetadata( XmlFieldMetadata.ID );

            if ( xmlFieldMetadata.isAttribute() )
            {
                String tagName = resolveTagName( field, xmlFieldMetadata );

                sc.add( "else if ( \"" + tagName + "\".equals( name ) )");
                sc.add(  "{" );
                sc.indent();

                writePrimitiveField( field, field.getType(), objectName, objectName, "\"" + field.getName() + "\"",
                                     "set" + capitalise( field.getName() ), sc, jClass );

                sc.unindent();
                sc.add( "}" );
            }
            // TODO check if we have already one with this type and throws Exception
            if ( xmlFieldMetadata.isContent() )
            {
                contentField = field;
            }
        }
        sc.add( "else" );

        sc.add( "{" );
        sc.addIndented( "checkUnknownAttribute( parser, name, tagName, strict );" );
        sc.add( "}" );

        sc.unindent();
        sc.add( "}" );

        return contentField;
    }

    /**
     * Generate code to process a field represented as an XML element.
     *
     * @param field the field to process
     * @param xmlFieldMetadata its XML metadata
     * @param addElse add an <code>else</code> statement before generating a new <code>if</code>
     * @param sc the method source code to add to
     * @param objectName the object name in the source
     * @param jClass the generated class source file
     */
    private void processField( ModelField field, XmlFieldMetadata xmlFieldMetadata, boolean addElse, JSourceCode sc,
                               String objectName, JClass jClass )
    {
        String fieldTagName = resolveTagName( field, xmlFieldMetadata );

        String capFieldName = capitalise( field.getName() );

        String singularName = singular( field.getName() );

        String alias;
        if ( StringUtils.isEmpty( field.getAlias() ) )
        {
            alias = "null";
        }
        else
        {
            alias = "\"" + field.getAlias() + "\"";
        }

        String tagComparison = ( addElse ? "else " : "" )
            + "if ( checkFieldWithDuplicate( parser, \"" + fieldTagName + "\", " + alias + ", parsed ) )";

        if ( !( field instanceof ModelAssociation ) )
        {
            //ModelField
            sc.add( tagComparison );

            sc.add( "{" );
            sc.indent();

            writePrimitiveField( field, field.getType(), objectName, objectName, "\"" + field.getName() + "\"", "set"
                + capitalise( field.getName() ), sc, jClass );

            sc.unindent();
            sc.add( "}" );
        }
        else
        { // model association
            ModelAssociation association = (ModelAssociation) field;

            String associationName = association.getName();

            if ( association.isOneMultiplicity() )
            {
                sc.add( tagComparison );

                sc.add( "{" );
                sc.addIndented( objectName + ".set" + capFieldName + "( parse" + association.getTo()
                    + "( parser, strict" + trackingArgs + " ) );" );
                sc.add( "}" );
            }
            else
            {
                //MANY_MULTIPLICITY

                XmlAssociationMetadata xmlAssociationMetadata =
                    (XmlAssociationMetadata) association.getAssociationMetadata( XmlAssociationMetadata.ID );

                String valuesTagName = resolveTagName( fieldTagName, xmlAssociationMetadata );

                String type = association.getType();

                if ( ModelDefault.LIST.equals( type ) || ModelDefault.SET.equals( type ) )
                {
                    boolean wrappedItems = xmlAssociationMetadata.isWrappedItems();

                    boolean inModel = isClassInModel( association.getTo(), field.getModelClass().getModel() );

                    if ( wrappedItems )
                    {
                        sc.add( tagComparison );

                        sc.add( "{" );
                        sc.indent();

                        sc.add( type + " " + associationName + " = " + association.getDefaultValue() + ";" );

                        sc.add( objectName + ".set" + capFieldName + "( " + associationName + " );" );

                        if ( !inModel && locationTracker != null )
                        {
                            sc.add( locationTracker.getName() + " " + LOCATION_VAR + "s;" );
                            writeNewSetLocation( field, objectName, LOCATION_VAR + "s", sc );
                        }

                        sc.add( "while ( parser.nextTag() == XmlPullParser.START_TAG )" );

                        sc.add( "{" );
                        sc.indent();

                        sc.add( "if ( \"" + valuesTagName + "\".equals( parser.getName() ) )" );

                        sc.add( "{" );
                        sc.indent();
                    }
                    else
                    {
                        sc.add( ( addElse ? "else " : "" )
                            + "if ( \"" + valuesTagName + "\".equals( parser.getName() ) )" );

                        sc.add( "{" );
                        sc.indent();

                        sc.add( type + " " + associationName + " = " + objectName + ".get" + capFieldName + "();" );

                        sc.add( "if ( " + associationName + " == null )" );

                        sc.add( "{" );
                        sc.indent();

                        sc.add( associationName + " = " + association.getDefaultValue() + ";" );

                        sc.add( objectName + ".set" + capFieldName + "( " + associationName + " );" );

                        sc.unindent();
                        sc.add( "}" );

                        if ( !inModel && locationTracker != null )
                        {
                            sc.add( locationTracker.getName() + " " + LOCATION_VAR + "s = " + objectName + ".get"
                                + capitalise( singular( locationField ) ) + "( \"" + field.getName() + "\" );" );
                            sc.add( "if ( " + LOCATION_VAR + "s == null )" );
                            sc.add( "{" );
                            sc.indent();
                            writeNewSetLocation( field, objectName, LOCATION_VAR + "s", sc );
                            sc.unindent();
                            sc.add( "}" );
                        }
                    }

                    if ( inModel )
                    {
                        sc.add( associationName + ".add( parse" + association.getTo() + "( parser, strict"
                            + trackingArgs + " ) );" );
                    }
                    else
                    {
                        String key;
                        if ( ModelDefault.SET.equals( type ) )
                        {
                            key = "?";
                        }
                        else
                        {
                            key =
                                ( useJava5 ? "Integer.valueOf" : "new java.lang.Integer" ) + "( " + associationName
                                    + ".size() )";
                        }
                        writePrimitiveField( association, association.getTo(), associationName, LOCATION_VAR + "s",
                                             key, "add", sc, jClass );
                    }

                    if ( wrappedItems )
                    {
                        sc.unindent();
                        sc.add( "}" );

                        sc.add( "else" );

                        sc.add( "{" );
                        sc.addIndented( "checkUnknownElement( parser, strict );" );
                        sc.add( "}" );

                        sc.unindent();
                        sc.add( "}" );

                        sc.unindent();
                        sc.add( "}" );
                    }
                    else
                    {
                        sc.unindent();
                        sc.add( "}" );
                    }
                }
                else
                {
                    //Map or Properties

                    sc.add( tagComparison );

                    sc.add( "{" );
                    sc.indent();

                    if ( locationTracker != null )
                    {
                        sc.add( locationTracker.getName() + " " + LOCATION_VAR + "s;" );
                        writeNewSetLocation( field, objectName, LOCATION_VAR + "s", sc );
                    }

                    if ( xmlAssociationMetadata.isMapExplode() )
                    {
                        sc.add( "while ( parser.nextTag() == XmlPullParser.START_TAG )" );

                        sc.add( "{" );
                        sc.indent();

                        sc.add( "if ( \"" + valuesTagName + "\".equals( parser.getName() ) )" );

                        sc.add( "{" );
                        sc.indent();

                        sc.add( "String key = null;" );

                        sc.add( "String value = null;" );
                        
                        writeNewLocation( LOCATION_VAR, sc );

                        sc.add( "// " + xmlAssociationMetadata.getMapStyle() + " mode." );

                        sc.add( "while ( parser.nextTag() == XmlPullParser.START_TAG )" );

                        sc.add( "{" );
                        sc.indent();

                        sc.add( "if ( \"key\".equals( parser.getName() ) )" );

                        sc.add( "{" );
                        sc.addIndented( "key = parser.nextText();" );
                        sc.add( "}" );

                        sc.add( "else if ( \"value\".equals( parser.getName() ) )" );

                        sc.add( "{" );
                        sc.indent();
                        writeNewLocation( LOCATION_VAR, sc );
                        sc.add( "value = parser.nextText()" + ( xmlFieldMetadata.isTrim() ? ".trim()" : "" ) + ";" );
                        sc.unindent();
                        sc.add( "}" );

                        sc.add( "else" );

                        sc.add( "{" );
                        sc.addIndented( "parser.nextText();" );
                        sc.add( "}" );

                        sc.unindent();
                        sc.add( "}" );

                        sc.add( objectName + ".add" + capitalise( singularName ) + "( key, value );" );
                        writeSetLocation( "key", LOCATION_VAR + "s", LOCATION_VAR, sc );

                        sc.unindent();
                        sc.add( "}" );

                        sc.add( "parser.next();" );

                        sc.unindent();
                        sc.add( "}" );
                    }
                    else
                    {
                        //INLINE Mode

                        sc.add( "while ( parser.nextTag() == XmlPullParser.START_TAG )" );

                        sc.add( "{" );
                        sc.indent();

                        sc.add( "String key = parser.getName();" );

                        writeNewSetLocation( "key", LOCATION_VAR + "s", null, sc );

                        sc.add( "String value = parser.nextText()" + ( xmlFieldMetadata.isTrim() ? ".trim()" : "" )
                                + ";" );

                        sc.add( objectName + ".add" + capitalise( singularName ) + "( key, value );" );

                        sc.unindent();
                        sc.add( "}" );
                    }

                    sc.unindent();
                    sc.add( "}" );
                }
            }
        }
    }

    private void writePrimitiveField( ModelField field, String type, String objectName, String locatorName,
                                      String locationKey, String setterName, JSourceCode sc, JClass jClass )
    {
        XmlFieldMetadata xmlFieldMetadata = (XmlFieldMetadata) field.getMetadata( XmlFieldMetadata.ID );

        String tagName = resolveTagName( field, xmlFieldMetadata);

        String parserGetter;
        if ( xmlFieldMetadata.isAttribute() )
        {
            parserGetter = "value"; // local variable created in the parsing block
        }
        else
        {
            parserGetter = "parser.nextText()";
        }

/* TODO: this and a default
        if ( xmlFieldMetadata.isRequired() )
        {
            parserGetter = "getRequiredAttributeValue( " + parserGetter + ", \"" + tagName + "\", parser, strict )";
        }
*/

        if ( xmlFieldMetadata.isTrim() )
        {
            parserGetter = "getTrimmedValue( " + parserGetter + " )";
        }

        String keyCapture = "";
        writeNewLocation( null, sc );
        if ( locationTracker != null && "?".equals( locationKey ) )
        {
            sc.add( "Object _key;" );
            locationKey = "_key";
            keyCapture = "_key = ";
        }
        else
        {
            writeSetLocation( locationKey, locatorName, null, sc );
        }

        if ( "boolean".equals( type ) )
        {
            sc.add( objectName + "." + setterName + "( " + keyCapture + "getBooleanValue( " + parserGetter + ", \""
                + tagName + "\", parser, \"" + field.getDefaultValue() + "\" ) );" );
        }
        else if ( "char".equals( type ) )
        {
            sc.add( objectName + "." + setterName + "( " + keyCapture + "getCharacterValue( " + parserGetter + ", \""
                + tagName + "\", parser ) );" );
        }
        else if ( "double".equals( type ) )
        {
            sc.add( objectName + "." + setterName + "( " + keyCapture + "getDoubleValue( " + parserGetter + ", \""
                + tagName + "\", parser, strict ) );" );
        }
        else if ( "float".equals( type ) )
        {
            sc.add( objectName + "." + setterName + "( " + keyCapture + "getFloatValue( " + parserGetter + ", \""
                + tagName + "\", parser, strict ) );" );
        }
        else if ( "int".equals( type ) )
        {
            sc.add( objectName + "." + setterName + "( " + keyCapture + "getIntegerValue( " + parserGetter + ", \""
                + tagName + "\", parser, strict ) );" );
        }
        else if ( "long".equals( type ) )
        {
            sc.add( objectName + "." + setterName + "( " + keyCapture + "getLongValue( " + parserGetter + ", \""
                + tagName + "\", parser, strict ) );" );
        }
        else if ( "short".equals( type ) )
        {
            sc.add( objectName + "." + setterName + "( " + keyCapture + "getShortValue( " + parserGetter + ", \""
                + tagName + "\", parser, strict ) );" );
        }
        else if ( "byte".equals( type ) )
        {
            sc.add( objectName + "." + setterName + "( " + keyCapture + "getByteValue( " + parserGetter + ", \""
                + tagName + "\", parser, strict ) );" );
        }
        else if ( "String".equals( type ) || "Boolean".equals( type ) )
        {
            // TODO: other Primitive types
            sc.add( objectName + "." + setterName + "( " + keyCapture +  parserGetter + " );" );
        }
        else if ( "Date".equals( type ) )
        {
            sc.add( "String dateFormat = " +
                ( xmlFieldMetadata.getFormat() != null ? "\"" + xmlFieldMetadata.getFormat() + "\"" : "null" ) + ";" );
            sc.add( objectName + "." + setterName + "( " + keyCapture + "getDateValue( " + parserGetter + ", \""
                + tagName + "\", dateFormat, parser ) );" );
        }
        else if ( "DOM".equals( type ) )
        {
            jClass.addImport( "org.codehaus.plexus.util.xml.Xpp3DomBuilder" );

            sc.add( objectName + "." + setterName + "( " + keyCapture + "Xpp3DomBuilder.build( parser ) );" );
        }
        else
        {
            throw new IllegalArgumentException( "Unknown type: " + type );
        }

        if ( keyCapture.length() > 0 )
        {
            writeSetLocation( locationKey, locatorName, null, sc );
        }
    }

    private void writeParserInitialization( JSourceCode sc )
    {
        sc.add( "if ( addDefaultEntities )" );

        sc.add( "{" );
        sc.indent();

        sc.add( "// ----------------------------------------------------------------------" );
        sc.add( "// Latin 1 entities" );
        sc.add( "// ----------------------------------------------------------------------" );
        sc.add( "" );
        sc.add( "parser.defineEntityReplacementText( \"nbsp\", \"\\u00a0\" );" );
        sc.add( "parser.defineEntityReplacementText( \"iexcl\", \"\\u00a1\" );" );
        sc.add( "parser.defineEntityReplacementText( \"cent\", \"\\u00a2\" );" );
        sc.add( "parser.defineEntityReplacementText( \"pound\", \"\\u00a3\" );" );
        sc.add( "parser.defineEntityReplacementText( \"curren\", \"\\u00a4\" );" );
        sc.add( "parser.defineEntityReplacementText( \"yen\", \"\\u00a5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"brvbar\", \"\\u00a6\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sect\", \"\\u00a7\" );" );
        sc.add( "parser.defineEntityReplacementText( \"uml\", \"\\u00a8\" );" );
        sc.add( "parser.defineEntityReplacementText( \"copy\", \"\\u00a9\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ordf\", \"\\u00aa\" );" );
        sc.add( "parser.defineEntityReplacementText( \"laquo\", \"\\u00ab\" );" );
        sc.add( "parser.defineEntityReplacementText( \"not\", \"\\u00ac\" );" );
        sc.add( "parser.defineEntityReplacementText( \"shy\", \"\\u00ad\" );" );
        sc.add( "parser.defineEntityReplacementText( \"reg\", \"\\u00ae\" );" );
        sc.add( "parser.defineEntityReplacementText( \"macr\", \"\\u00af\" );" );
        sc.add( "parser.defineEntityReplacementText( \"deg\", \"\\u00b0\" );" );
        sc.add( "parser.defineEntityReplacementText( \"plusmn\", \"\\u00b1\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sup2\", \"\\u00b2\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sup3\", \"\\u00b3\" );" );
        sc.add( "parser.defineEntityReplacementText( \"acute\", \"\\u00b4\" );" );
        sc.add( "parser.defineEntityReplacementText( \"micro\", \"\\u00b5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"para\", \"\\u00b6\" );" );
        sc.add( "parser.defineEntityReplacementText( \"middot\", \"\\u00b7\" );" );
        sc.add( "parser.defineEntityReplacementText( \"cedil\", \"\\u00b8\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sup1\", \"\\u00b9\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ordm\", \"\\u00ba\" );" );
        sc.add( "parser.defineEntityReplacementText( \"raquo\", \"\\u00bb\" );" );
        sc.add( "parser.defineEntityReplacementText( \"frac14\", \"\\u00bc\" );" );
        sc.add( "parser.defineEntityReplacementText( \"frac12\", \"\\u00bd\" );" );
        sc.add( "parser.defineEntityReplacementText( \"frac34\", \"\\u00be\" );" );
        sc.add( "parser.defineEntityReplacementText( \"iquest\", \"\\u00bf\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Agrave\", \"\\u00c0\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Aacute\", \"\\u00c1\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Acirc\", \"\\u00c2\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Atilde\", \"\\u00c3\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Auml\", \"\\u00c4\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Aring\", \"\\u00c5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"AElig\", \"\\u00c6\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Ccedil\", \"\\u00c7\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Egrave\", \"\\u00c8\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Eacute\", \"\\u00c9\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Ecirc\", \"\\u00ca\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Euml\", \"\\u00cb\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Igrave\", \"\\u00cc\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Iacute\", \"\\u00cd\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Icirc\", \"\\u00ce\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Iuml\", \"\\u00cf\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ETH\", \"\\u00d0\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Ntilde\", \"\\u00d1\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Ograve\", \"\\u00d2\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Oacute\", \"\\u00d3\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Ocirc\", \"\\u00d4\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Otilde\", \"\\u00d5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Ouml\", \"\\u00d6\" );" );
        sc.add( "parser.defineEntityReplacementText( \"times\", \"\\u00d7\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Oslash\", \"\\u00d8\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Ugrave\", \"\\u00d9\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Uacute\", \"\\u00da\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Ucirc\", \"\\u00db\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Uuml\", \"\\u00dc\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Yacute\", \"\\u00dd\" );" );
        sc.add( "parser.defineEntityReplacementText( \"THORN\", \"\\u00de\" );" );
        sc.add( "parser.defineEntityReplacementText( \"szlig\", \"\\u00df\" );" );
        sc.add( "parser.defineEntityReplacementText( \"agrave\", \"\\u00e0\" );" );
        sc.add( "parser.defineEntityReplacementText( \"aacute\", \"\\u00e1\" );" );
        sc.add( "parser.defineEntityReplacementText( \"acirc\", \"\\u00e2\" );" );
        sc.add( "parser.defineEntityReplacementText( \"atilde\", \"\\u00e3\" );" );
        sc.add( "parser.defineEntityReplacementText( \"auml\", \"\\u00e4\" );" );
        sc.add( "parser.defineEntityReplacementText( \"aring\", \"\\u00e5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"aelig\", \"\\u00e6\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ccedil\", \"\\u00e7\" );" );
        sc.add( "parser.defineEntityReplacementText( \"egrave\", \"\\u00e8\" );" );
        sc.add( "parser.defineEntityReplacementText( \"eacute\", \"\\u00e9\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ecirc\", \"\\u00ea\" );" );
        sc.add( "parser.defineEntityReplacementText( \"euml\", \"\\u00eb\" );" );
        sc.add( "parser.defineEntityReplacementText( \"igrave\", \"\\u00ec\" );" );
        sc.add( "parser.defineEntityReplacementText( \"iacute\", \"\\u00ed\" );" );
        sc.add( "parser.defineEntityReplacementText( \"icirc\", \"\\u00ee\" );" );
        sc.add( "parser.defineEntityReplacementText( \"iuml\", \"\\u00ef\" );" );
        sc.add( "parser.defineEntityReplacementText( \"eth\", \"\\u00f0\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ntilde\", \"\\u00f1\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ograve\", \"\\u00f2\" );" );
        sc.add( "parser.defineEntityReplacementText( \"oacute\", \"\\u00f3\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ocirc\", \"\\u00f4\" );" );
        sc.add( "parser.defineEntityReplacementText( \"otilde\", \"\\u00f5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ouml\", \"\\u00f6\" );" );
        sc.add( "parser.defineEntityReplacementText( \"divide\", \"\\u00f7\" );" );
        sc.add( "parser.defineEntityReplacementText( \"oslash\", \"\\u00f8\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ugrave\", \"\\u00f9\" );" );
        sc.add( "parser.defineEntityReplacementText( \"uacute\", \"\\u00fa\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ucirc\", \"\\u00fb\" );" );
        sc.add( "parser.defineEntityReplacementText( \"uuml\", \"\\u00fc\" );" );
        sc.add( "parser.defineEntityReplacementText( \"yacute\", \"\\u00fd\" );" );
        sc.add( "parser.defineEntityReplacementText( \"thorn\", \"\\u00fe\" );" );
        sc.add( "parser.defineEntityReplacementText( \"yuml\", \"\\u00ff\" );" );
        sc.add( "" );
        sc.add( "// ----------------------------------------------------------------------" );
        sc.add( "// Special entities" );
        sc.add( "// ----------------------------------------------------------------------" );
        sc.add( "" );
        // These are required to be handled by the parser by the XML specification
//        sc.add( "parser.defineEntityReplacementText( \"quot\", \"\\u0022\" );" );
//        sc.add( "parser.defineEntityReplacementText( \"amp\", \"\\u0026\" );" );
//        sc.add( "parser.defineEntityReplacementText( \"lt\", \"\\u003c\" );" );
//        sc.add( "parser.defineEntityReplacementText( \"gt\", \"\\u003e\" );" );
//        sc.add( "parser.defineEntityReplacementText( \"apos\", \"\\u0027\" );" );
        sc.add( "parser.defineEntityReplacementText( \"OElig\", \"\\u0152\" );" );
        sc.add( "parser.defineEntityReplacementText( \"oelig\", \"\\u0153\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Scaron\", \"\\u0160\" );" );
        sc.add( "parser.defineEntityReplacementText( \"scaron\", \"\\u0161\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Yuml\", \"\\u0178\" );" );
        sc.add( "parser.defineEntityReplacementText( \"circ\", \"\\u02c6\" );" );
        sc.add( "parser.defineEntityReplacementText( \"tilde\", \"\\u02dc\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ensp\", \"\\u2002\" );" );
        sc.add( "parser.defineEntityReplacementText( \"emsp\", \"\\u2003\" );" );
        sc.add( "parser.defineEntityReplacementText( \"thinsp\", \"\\u2009\" );" );
        sc.add( "parser.defineEntityReplacementText( \"zwnj\", \"\\u200c\" );" );
        sc.add( "parser.defineEntityReplacementText( \"zwj\", \"\\u200d\" );" );
        sc.add( "parser.defineEntityReplacementText( \"lrm\", \"\\u200e\" );" );
        sc.add( "parser.defineEntityReplacementText( \"rlm\", \"\\u200f\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ndash\", \"\\u2013\" );" );
        sc.add( "parser.defineEntityReplacementText( \"mdash\", \"\\u2014\" );" );
        sc.add( "parser.defineEntityReplacementText( \"lsquo\", \"\\u2018\" );" );
        sc.add( "parser.defineEntityReplacementText( \"rsquo\", \"\\u2019\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sbquo\", \"\\u201a\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ldquo\", \"\\u201c\" );" );
        sc.add( "parser.defineEntityReplacementText( \"rdquo\", \"\\u201d\" );" );
        sc.add( "parser.defineEntityReplacementText( \"bdquo\", \"\\u201e\" );" );
        sc.add( "parser.defineEntityReplacementText( \"dagger\", \"\\u2020\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Dagger\", \"\\u2021\" );" );
        sc.add( "parser.defineEntityReplacementText( \"permil\", \"\\u2030\" );" );
        sc.add( "parser.defineEntityReplacementText( \"lsaquo\", \"\\u2039\" );" );
        sc.add( "parser.defineEntityReplacementText( \"rsaquo\", \"\\u203a\" );" );
        sc.add( "parser.defineEntityReplacementText( \"euro\", \"\\u20ac\" );" );
        sc.add( "" );
        sc.add( "// ----------------------------------------------------------------------" );
        sc.add( "// Symbol entities" );
        sc.add( "// ----------------------------------------------------------------------" );
        sc.add( "" );
        sc.add( "parser.defineEntityReplacementText( \"fnof\", \"\\u0192\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Alpha\", \"\\u0391\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Beta\", \"\\u0392\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Gamma\", \"\\u0393\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Delta\", \"\\u0394\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Epsilon\", \"\\u0395\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Zeta\", \"\\u0396\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Eta\", \"\\u0397\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Theta\", \"\\u0398\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Iota\", \"\\u0399\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Kappa\", \"\\u039a\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Lambda\", \"\\u039b\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Mu\", \"\\u039c\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Nu\", \"\\u039d\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Xi\", \"\\u039e\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Omicron\", \"\\u039f\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Pi\", \"\\u03a0\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Rho\", \"\\u03a1\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Sigma\", \"\\u03a3\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Tau\", \"\\u03a4\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Upsilon\", \"\\u03a5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Phi\", \"\\u03a6\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Chi\", \"\\u03a7\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Psi\", \"\\u03a8\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Omega\", \"\\u03a9\" );" );
        sc.add( "parser.defineEntityReplacementText( \"alpha\", \"\\u03b1\" );" );
        sc.add( "parser.defineEntityReplacementText( \"beta\", \"\\u03b2\" );" );
        sc.add( "parser.defineEntityReplacementText( \"gamma\", \"\\u03b3\" );" );
        sc.add( "parser.defineEntityReplacementText( \"delta\", \"\\u03b4\" );" );
        sc.add( "parser.defineEntityReplacementText( \"epsilon\", \"\\u03b5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"zeta\", \"\\u03b6\" );" );
        sc.add( "parser.defineEntityReplacementText( \"eta\", \"\\u03b7\" );" );
        sc.add( "parser.defineEntityReplacementText( \"theta\", \"\\u03b8\" );" );
        sc.add( "parser.defineEntityReplacementText( \"iota\", \"\\u03b9\" );" );
        sc.add( "parser.defineEntityReplacementText( \"kappa\", \"\\u03ba\" );" );
        sc.add( "parser.defineEntityReplacementText( \"lambda\", \"\\u03bb\" );" );
        sc.add( "parser.defineEntityReplacementText( \"mu\", \"\\u03bc\" );" );
        sc.add( "parser.defineEntityReplacementText( \"nu\", \"\\u03bd\" );" );
        sc.add( "parser.defineEntityReplacementText( \"xi\", \"\\u03be\" );" );
        sc.add( "parser.defineEntityReplacementText( \"omicron\", \"\\u03bf\" );" );
        sc.add( "parser.defineEntityReplacementText( \"pi\", \"\\u03c0\" );" );
        sc.add( "parser.defineEntityReplacementText( \"rho\", \"\\u03c1\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sigmaf\", \"\\u03c2\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sigma\", \"\\u03c3\" );" );
        sc.add( "parser.defineEntityReplacementText( \"tau\", \"\\u03c4\" );" );
        sc.add( "parser.defineEntityReplacementText( \"upsilon\", \"\\u03c5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"phi\", \"\\u03c6\" );" );
        sc.add( "parser.defineEntityReplacementText( \"chi\", \"\\u03c7\" );" );
        sc.add( "parser.defineEntityReplacementText( \"psi\", \"\\u03c8\" );" );
        sc.add( "parser.defineEntityReplacementText( \"omega\", \"\\u03c9\" );" );
        sc.add( "parser.defineEntityReplacementText( \"thetasym\", \"\\u03d1\" );" );
        sc.add( "parser.defineEntityReplacementText( \"upsih\", \"\\u03d2\" );" );
        sc.add( "parser.defineEntityReplacementText( \"piv\", \"\\u03d6\" );" );
        sc.add( "parser.defineEntityReplacementText( \"bull\", \"\\u2022\" );" );
        sc.add( "parser.defineEntityReplacementText( \"hellip\", \"\\u2026\" );" );
        sc.add( "parser.defineEntityReplacementText( \"prime\", \"\\u2032\" );" );
        sc.add( "parser.defineEntityReplacementText( \"Prime\", \"\\u2033\" );" );
        sc.add( "parser.defineEntityReplacementText( \"oline\", \"\\u203e\" );" );
        sc.add( "parser.defineEntityReplacementText( \"frasl\", \"\\u2044\" );" );
        sc.add( "parser.defineEntityReplacementText( \"weierp\", \"\\u2118\" );" );
        sc.add( "parser.defineEntityReplacementText( \"image\", \"\\u2111\" );" );
        sc.add( "parser.defineEntityReplacementText( \"real\", \"\\u211c\" );" );
        sc.add( "parser.defineEntityReplacementText( \"trade\", \"\\u2122\" );" );
        sc.add( "parser.defineEntityReplacementText( \"alefsym\", \"\\u2135\" );" );
        sc.add( "parser.defineEntityReplacementText( \"larr\", \"\\u2190\" );" );
        sc.add( "parser.defineEntityReplacementText( \"uarr\", \"\\u2191\" );" );
        sc.add( "parser.defineEntityReplacementText( \"rarr\", \"\\u2192\" );" );
        sc.add( "parser.defineEntityReplacementText( \"darr\", \"\\u2193\" );" );
        sc.add( "parser.defineEntityReplacementText( \"harr\", \"\\u2194\" );" );
        sc.add( "parser.defineEntityReplacementText( \"crarr\", \"\\u21b5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"lArr\", \"\\u21d0\" );" );
        sc.add( "parser.defineEntityReplacementText( \"uArr\", \"\\u21d1\" );" );
        sc.add( "parser.defineEntityReplacementText( \"rArr\", \"\\u21d2\" );" );
        sc.add( "parser.defineEntityReplacementText( \"dArr\", \"\\u21d3\" );" );
        sc.add( "parser.defineEntityReplacementText( \"hArr\", \"\\u21d4\" );" );
        sc.add( "parser.defineEntityReplacementText( \"forall\", \"\\u2200\" );" );
        sc.add( "parser.defineEntityReplacementText( \"part\", \"\\u2202\" );" );
        sc.add( "parser.defineEntityReplacementText( \"exist\", \"\\u2203\" );" );
        sc.add( "parser.defineEntityReplacementText( \"empty\", \"\\u2205\" );" );
        sc.add( "parser.defineEntityReplacementText( \"nabla\", \"\\u2207\" );" );
        sc.add( "parser.defineEntityReplacementText( \"isin\", \"\\u2208\" );" );
        sc.add( "parser.defineEntityReplacementText( \"notin\", \"\\u2209\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ni\", \"\\u220b\" );" );
        sc.add( "parser.defineEntityReplacementText( \"prod\", \"\\u220f\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sum\", \"\\u2211\" );" );
        sc.add( "parser.defineEntityReplacementText( \"minus\", \"\\u2212\" );" );
        sc.add( "parser.defineEntityReplacementText( \"lowast\", \"\\u2217\" );" );
        sc.add( "parser.defineEntityReplacementText( \"radic\", \"\\u221a\" );" );
        sc.add( "parser.defineEntityReplacementText( \"prop\", \"\\u221d\" );" );
        sc.add( "parser.defineEntityReplacementText( \"infin\", \"\\u221e\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ang\", \"\\u2220\" );" );
        sc.add( "parser.defineEntityReplacementText( \"and\", \"\\u2227\" );" );
        sc.add( "parser.defineEntityReplacementText( \"or\", \"\\u2228\" );" );
        sc.add( "parser.defineEntityReplacementText( \"cap\", \"\\u2229\" );" );
        sc.add( "parser.defineEntityReplacementText( \"cup\", \"\\u222a\" );" );
        sc.add( "parser.defineEntityReplacementText( \"int\", \"\\u222b\" );" );
        sc.add( "parser.defineEntityReplacementText( \"there4\", \"\\u2234\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sim\", \"\\u223c\" );" );
        sc.add( "parser.defineEntityReplacementText( \"cong\", \"\\u2245\" );" );
        sc.add( "parser.defineEntityReplacementText( \"asymp\", \"\\u2248\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ne\", \"\\u2260\" );" );
        sc.add( "parser.defineEntityReplacementText( \"equiv\", \"\\u2261\" );" );
        sc.add( "parser.defineEntityReplacementText( \"le\", \"\\u2264\" );" );
        sc.add( "parser.defineEntityReplacementText( \"ge\", \"\\u2265\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sub\", \"\\u2282\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sup\", \"\\u2283\" );" );
        sc.add( "parser.defineEntityReplacementText( \"nsub\", \"\\u2284\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sube\", \"\\u2286\" );" );
        sc.add( "parser.defineEntityReplacementText( \"supe\", \"\\u2287\" );" );
        sc.add( "parser.defineEntityReplacementText( \"oplus\", \"\\u2295\" );" );
        sc.add( "parser.defineEntityReplacementText( \"otimes\", \"\\u2297\" );" );
        sc.add( "parser.defineEntityReplacementText( \"perp\", \"\\u22a5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"sdot\", \"\\u22c5\" );" );
        sc.add( "parser.defineEntityReplacementText( \"lceil\", \"\\u2308\" );" );
        sc.add( "parser.defineEntityReplacementText( \"rceil\", \"\\u2309\" );" );
        sc.add( "parser.defineEntityReplacementText( \"lfloor\", \"\\u230a\" );" );
        sc.add( "parser.defineEntityReplacementText( \"rfloor\", \"\\u230b\" );" );
        sc.add( "parser.defineEntityReplacementText( \"lang\", \"\\u2329\" );" );
        sc.add( "parser.defineEntityReplacementText( \"rang\", \"\\u232a\" );" );
        sc.add( "parser.defineEntityReplacementText( \"loz\", \"\\u25ca\" );" );
        sc.add( "parser.defineEntityReplacementText( \"spades\", \"\\u2660\" );" );
        sc.add( "parser.defineEntityReplacementText( \"clubs\", \"\\u2663\" );" );
        sc.add( "parser.defineEntityReplacementText( \"hearts\", \"\\u2665\" );" );
        sc.add( "parser.defineEntityReplacementText( \"diams\", \"\\u2666\" );" );
        sc.add( "" );

        sc.unindent();
        sc.add( "}" );
    }

    private void writeHelpers( JClass jClass )
    {
        JMethod method = new JMethod( "getTrimmedValue", new JClass( "String" ), null );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "String" ), "s" ) );

        JSourceCode sc = method.getSourceCode();

        sc.add( "if ( s != null )" );

        sc.add( "{" );
        sc.addIndented( "s = s.trim();" );
        sc.add( "}" );

        sc.add( "return s;" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = new JMethod( "getRequiredAttributeValue", new JClass( "String" ), null );
        method.addException( new JClass( "XmlPullParserException" ) );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "String" ), "s" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "attribute" ) );
        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );
        method.addParameter( new JParameter( JClass.BOOLEAN, "strict" ) );

        sc = method.getSourceCode();

        sc.add( "if ( s == null )" );

        sc.add( "{" );
        sc.indent();

        sc.add( "if ( strict )" );

        sc.add( "{" );
        sc.addIndented(
            "throw new XmlPullParserException( \"Missing required value for attribute '\" + attribute + \"'\", parser, null );" );
        sc.add( "}" );

        sc.unindent();
        sc.add( "}" );

        sc.add( "return s;" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = new JMethod( "getBooleanValue", JType.BOOLEAN, null );
        method.addException( new JClass( "XmlPullParserException" ) );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "String" ), "s" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "attribute" ) );
        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );

        sc = method.getSourceCode();

        sc.add( "return getBooleanValue( s, attribute, parser, null );" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = new JMethod( "getBooleanValue", JType.BOOLEAN, null );
        method.addException( new JClass( "XmlPullParserException" ) );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "String" ), "s" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "attribute" ) );
        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "defaultValue" ) );

        sc = method.getSourceCode();

        sc.add( "if ( s != null && s.length() != 0 )" );

        sc.add( "{" );
        sc.addIndented( "return Boolean.valueOf( s ).booleanValue();" );
        sc.add( "}" );

        sc.add( "if ( defaultValue != null )" );

        sc.add( "{" );
        sc.addIndented( "return Boolean.valueOf( defaultValue ).booleanValue();" );
        sc.add( "}" );

        sc.add( "return false;" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = new JMethod( "getCharacterValue", JType.CHAR, null );
        method.addException( new JClass( "XmlPullParserException" ) );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "String" ), "s" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "attribute" ) );
        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );

        sc = method.getSourceCode();

        sc.add( "if ( s != null )" );

        sc.add( "{" );
        sc.addIndented( "return s.charAt( 0 );" );
        sc.add( "}" );

        sc.add( "return 0;" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = convertNumericalType( "getIntegerValue", JType.INT, "Integer.valueOf( s ).intValue()", "an integer" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = convertNumericalType( "getShortValue", JType.SHORT, "Short.valueOf( s ).shortValue()",
                                       "a short integer" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = convertNumericalType( "getByteValue", JType.BYTE, "Byte.valueOf( s ).byteValue()", "a byte" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = convertNumericalType( "getLongValue", JType.LONG, "Long.valueOf( s ).longValue()", "a long integer" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = convertNumericalType( "getFloatValue", JType.FLOAT, "Float.valueOf( s ).floatValue()",
                                       "a floating point number" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = convertNumericalType( "getDoubleValue", JType.DOUBLE, "Double.valueOf( s ).doubleValue()",
                                       "a floating point number" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = new JMethod( "getDateValue", new JClass( "java.util.Date" ), null );
        method.addException( new JClass( "XmlPullParserException" ) );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "String" ), "s" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "attribute" ) );
        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );
        method.addException( new JClass( "XmlPullParserException" ) );

        sc = method.getSourceCode();

        sc.add( "return getDateValue( s, attribute, null, parser );" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = new JMethod( "getDateValue", new JClass( "java.util.Date" ), null );
        method.addException( new JClass( "XmlPullParserException" ) );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "String" ), "s" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "attribute" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "dateFormat" ) );
        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );
        method.addException( new JClass( "XmlPullParserException" ) );

        writeDateParsingHelper( method.getSourceCode(), "new XmlPullParserException( e.getMessage(), parser, e )" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = new JMethod( "checkFieldWithDuplicate", JType.BOOLEAN, null );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "tagName" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "alias" ) );
        method.addParameter( new JParameter( new JClass( "java.util.Set" ), "parsed" ) );
        method.addException( new JClass( "XmlPullParserException" ) );

        sc = method.getSourceCode();

        sc.add( "if ( !( parser.getName().equals( tagName ) || parser.getName().equals( alias ) ) )" );

        sc.add( "{" );
        sc.addIndented( "return false;" );
        sc.add( "}" );

        sc.add( "if ( !parsed.add( tagName ) )" );

        sc.add( "{" );
        sc.addIndented(
            "throw new XmlPullParserException( \"Duplicated tag: '\" + tagName + \"'\", parser, null );" );
        sc.add( "}" );

        sc.add( "return true;" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = new JMethod( "checkUnknownElement", null, null );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );
        method.addParameter( new JParameter( JType.BOOLEAN, "strict" ) );
        method.addException( new JClass( "XmlPullParserException" ) );
        method.addException( new JClass( "IOException" ) );

        sc = method.getSourceCode();

        sc.add( "if ( strict )" );

        sc.add( "{" );
        sc.addIndented(
            "throw new XmlPullParserException( \"Unrecognised tag: '\" + parser.getName() + \"'\", parser, null );" );
        sc.add( "}" );

        sc.add( "" );

        sc.add( "for ( int unrecognizedTagCount = 1; unrecognizedTagCount > 0; )" );
        sc.add( "{" );
        sc.indent();
        sc.add( "int eventType = parser.next();" );
        sc.add( "if ( eventType == XmlPullParser.START_TAG )" );
        sc.add( "{" );
        sc.addIndented( "unrecognizedTagCount++;" );
        sc.add( "}" );
        sc.add( "else if ( eventType == XmlPullParser.END_TAG )" );
        sc.add( "{" );
        sc.addIndented( "unrecognizedTagCount--;" );
        sc.add( "}" );
        sc.unindent();
        sc.add( "}" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = new JMethod( "checkUnknownAttribute", null, null );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "attribute" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "tagName" ) );
        method.addParameter( new JParameter( JType.BOOLEAN, "strict" ) );
        method.addException( new JClass( "XmlPullParserException" ) );
        method.addException( new JClass( "IOException" ) );

        sc = method.getSourceCode();

        if ( strictXmlAttributes )
        {
            sc.add( "// strictXmlAttributes = true for model: if strict == true, not only elements are checked but attributes too" );
            sc.add( "if ( strict )" );

            sc.add( "{" );
            sc.addIndented( "throw new XmlPullParserException( \"Unknown attribute '\" + attribute + \"' for tag '\" + tagName + \"'\", parser, null );" );
            sc.add( "}" );
        }
        else
        {
            sc.add( "// strictXmlAttributes = false for model: always ignore unknown XML attribute, even if strict == true" );
        }

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        method = new JMethod( "nextTag", JType.INT, null );
        method.addException( new JClass( "IOException" ) );
        method.addException( new JClass( "XmlPullParserException" ) );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );

        sc = method.getSourceCode();

        sc.add( "int eventType = parser.next();" );
        sc.add( "if ( eventType == XmlPullParser.TEXT )" );
        sc.add( "{" );
        sc.addIndented( "eventType = parser.next();" );
        sc.add( "}" );
        sc.add( "if ( eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_TAG )" );
        sc.add( "{" );
        sc.addIndented( "throw new XmlPullParserException( \"expected START_TAG or END_TAG not \" + XmlPullParser.TYPES[eventType], parser, null );" );
        sc.add( "}" );
        sc.add( "return eventType;" );

        jClass.addMethod( method );

        // --------------------------------------------------------------------

        jClass.addMethod( initParser() );
    }

    private JMethod convertNumericalType( String methodName, JType returnType, String expression, String typeDesc )
    {
        JMethod method = new JMethod( methodName, returnType, null );
        method.addException( new JClass( "XmlPullParserException" ) );
        method.getModifiers().makePrivate();

        method.addParameter( new JParameter( new JClass( "String" ), "s" ) );
        method.addParameter( new JParameter( new JClass( "String" ), "attribute" ) );
        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );
        method.addParameter( new JParameter( JClass.BOOLEAN, "strict" ) );

        JSourceCode sc = method.getSourceCode();

        sc.add( "if ( s != null )" );

        sc.add( "{" );
        sc.indent();

        sc.add( "try" );

        sc.add( "{" );
        sc.addIndented( "return " + expression + ";" );
        sc.add( "}" );

        sc.add( "catch ( NumberFormatException nfe )" );

        sc.add( "{" );
        sc.indent();

        sc.add( "if ( strict )" );

        sc.add( "{" );
        sc.addIndented( "throw new XmlPullParserException( \"Unable to parse element '\" + attribute + \"', must be " +
            typeDesc + "\", parser, nfe );" );
        sc.add( "}" );

        sc.unindent();
        sc.add( "}" );

        sc.unindent();
        sc.add( "}" );

        sc.add( "return 0;" );

        return method;
    }

    private JMethod initParser()
    {
        JMethod method = new JMethod( "initParser" );
        method.addParameter( new JParameter( new JClass( "XmlPullParser" ), "parser" ) );
        method.addException( new JClass( "XmlPullParserException" ) );
        method.getModifiers().makePrivate();

        JSourceCode sc = method.getSourceCode();

        writeParserInitialization( sc );

        return method;
    }

    private void addTrackingParameters( JMethod method )
    {
        if ( sourceTracker != null )
        {
            method.addParameter( new JParameter( new JClass( sourceTracker.getName() ), SOURCE_PARAM ) );
        }
    }

    private void writeNewSetLocation( ModelField field, String objectName, String trackerVariable, JSourceCode sc )
    {
        writeNewSetLocation( "\"" + field.getName() + "\"", objectName, trackerVariable, sc );
    }

    private void writeNewSetLocation( String key, String objectName, String trackerVariable, JSourceCode sc )
    {
        writeNewLocation( trackerVariable, sc );
        writeSetLocation( key, objectName, trackerVariable, sc );
    }

    private void writeNewLocation( String trackerVariable, JSourceCode sc )
    {
        if ( locationTracker == null )
        {
            return;
        }

        String constr = "new " + locationTracker.getName() + "( parser.getLineNumber(), parser.getColumnNumber()";
        constr += ( sourceTracker != null ) ? ", " + SOURCE_PARAM : "";
        constr += " )";

        sc.add( ( ( trackerVariable != null ) ? trackerVariable : LOCATION_VAR ) + " = " + constr + ";" );
    }

    private void writeSetLocation( String key, String objectName, String trackerVariable, JSourceCode sc )
    {
        if ( locationTracker == null )
        {
            return;
        }

        String variable = ( trackerVariable != null ) ? trackerVariable : LOCATION_VAR;

        sc.add( objectName + ".set" + capitalise( singular( locationField ) ) + "( " + key + ", " + variable + " );" );
    }

}
