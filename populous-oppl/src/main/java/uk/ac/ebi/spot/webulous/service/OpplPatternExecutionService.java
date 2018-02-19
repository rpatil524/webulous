package uk.ac.ebi.spot.webulous.service;

import org.apache.commons.lang3.StringUtils;
import org.coode.oppl.Variable;
import org.coode.oppl.exceptions.QuickFailRuntimeExceptionHandler;
import org.coode.oppl.variabletypes.*;
import org.coode.parsers.BidirectionalShortFormProviderAdapter;
import org.coode.parsers.common.QuickFailErrorListener;
import org.coode.patterns.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.AnnotationValueShortFormProvider;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.ebi.spot.webulous.entity.CustomOWLEntityFactory;
import uk.ac.ebi.spot.webulous.entity.PseudoRandomAutoIDGenerator;
import uk.ac.ebi.spot.webulous.entity.SimpleEntityCreation;
import uk.ac.ebi.spot.webulous.exception.OWLEntityCreationException;
import uk.ac.ebi.spot.webulous.model.*;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;


/**
 * Author: Simon Jupp<br>
 * Date: Oct 7, 2010<br>
 * The University of Manchester<br>
 * Bio-Health Informatics Group<br>
 *
 * Modified: March, 2015 by Simon Jupp, European Bioinformatics Group
 * The PopulousPatternExecutorService takes a data collection, eg a spreadsheet, a PopulousModel, with OPPL patterns and variable bindings, and a entity creation strategy
 * and turns the data into ontology axioms based on the specified patterns.
 */



public class OpplPatternExecutionService {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private static final String SEPERATOR = "||";
    private String[][] dataCollection;

    private PopulousTemplate populousTemplate;
    private OWLOntologyManager ontologyManager;

    private OWLOntology activeOntology;

    private ParserFactory pf;
    private OPPLPatternParser parser;

    private HashMap<Integer, Map<String, IRI>> shortFromMapper;
    private BidirectionalShortFormProviderAdapter shortFormProvider;

    private List<OWLAnnotationProperty> props;

    private OWLEntityFactory owlEntityFactory;

    private URI defaultBaseUri;

    public OWLOntology executeOPPLPatterns(String ontologyUri, String[][] data, PopulousTemplate populousTemplate, List<String> errorCollector) throws OWLOntologyCreationException {
        SimpleEntityCreation entityCreation = new SimpleEntityCreation();
        entityCreation.setFragmentAutoGenerated(true);
        entityCreation.setDefaultBaseURI(ontologyUri);
        entityCreation.setAutoIDGeneratorClass(PseudoRandomAutoIDGenerator.class);
        entityCreation.setGenerateNameLabel(true);
        return executeOPPLPatterns(ontologyUri, data, populousTemplate, entityCreation, errorCollector);
    }

    public OWLOntology executeOPPLPatterns(String ontologyUri, String[][] data, PopulousTemplate populousTemplate, EntityCreation entityCreation, List<String> errorCollector) throws OWLOntologyCreationException {
        this.owlEntityFactory = new CustomOWLEntityFactory(getOntologyManager(), getActiveOntology(ontologyUri), entityCreation);
        return executeOPPLPatterns(ontologyUri, data, populousTemplate, owlEntityFactory, errorCollector);
    }

    public OWLOntology executeOPPLPatterns(String ontologyUri, String[][] data, PopulousTemplate populousTemplate, OWLEntityFactory owlEntityFactory, List<String> errorCollector) throws OWLOntologyCreationException {

        logger.debug("Starting Pattern Executor");
        this.dataCollection = data;
        this.populousTemplate = populousTemplate;

        for (String iri: populousTemplate.getOntologyImports())  {
            getOntologyManager().loadOntology(IRI.create(iri));
        }

        //set up an OWLEntityFactory
        this.owlEntityFactory = owlEntityFactory;
        defaultBaseUri = URI.create(ontologyUri);


        //set up an OPPL ParserFactory
        this.pf = new ParserFactory(getActiveOntology(ontologyUri), getOntologyManager());
        this.parser = pf.build(new QuickFailErrorListener());

        // set up short form provider
        IRI iri = OWLRDFVocabulary.RDFS_LABEL.getIRI();
        OWLAnnotationProperty prop = getOntologyManager().getOWLDataFactory().getOWLAnnotationProperty(iri);
        props = new ArrayList<OWLAnnotationProperty>();
        props.add(prop);
        ShortFormProvider provider = new AnnotationValueShortFormProvider(props, new HashMap<OWLAnnotationProperty, List<String>>(), getOntologyManager());
        shortFormProvider = new BidirectionalShortFormProviderAdapter(getOntologyManager(), getOntologyManager().getOntologies(), provider);
        shortFromMapper = createShortFormMapper(populousTemplate.getDataRestrictions());

        QuickFailRuntimeExceptionHandler handler = new QuickFailRuntimeExceptionHandler();

        try {
            validateAllPatterns(parser, populousTemplate.getPatterns());


            for (PopulousPattern pattern : populousTemplate.getPatterns()) {
                logger.debug("Got pattern: " + pattern.getPatternName() + "\n" + pattern.getPatternValue());

                //pass the OPPL pattern string to the parser for processing
                PatternModel patternModel = parser.parse(pattern.getPatternValue());

                List<OWLAxiomChange> changes = new ArrayList<OWLAxiomChange>();

                // get the pattern variables
                if (!patternModel.getInputVariables().isEmpty()) {

                    //create a map of the input variable names, eg "?disease" and the actual variable
                    Map<String, Variable> opplVariableMap = createOPPLVariableMap(patternModel);

                    int done = 0;
                    logger.debug("About to read " + dataCollection.length + " rows");
                    //process each row in the DataCollection, one by one
                    for (int x =0 ; x < dataCollection.length; x ++) {
                        // for (DataObject row : dataCollection.getDataObjects()) {
                        logger.debug("Reading row: " + x);
                        try {
                            //create an instantiated pattern model based on the data in the row
                            InstantiatedPatternModel ipm =  processDataRow(dataCollection[x], opplVariableMap, handler, patternModel);

                            //pass the instantiated pattern model to a patternExecutor and add the changes to the list of all changes for this model
                            NonClassPatternExecutor patternExecutor = new NonClassPatternExecutor(ipm, getActiveOntology(ontologyUri), getOntologyManager(), IRI.create("http://www.ebi.ac.uk/ontology/webulous#OPPL_pattern"), handler);
                            changes.addAll(patternExecutor.visit(patternModel));
                            done++;
                        } catch (RuntimeException e) {
                            errorCollector.add(e.getMessage());
                            logger.error("Error processing row " + done + ": " + e.getMessage(), e);
                        }
                    }

                }
                //if there are no input variables in the OPPL pattern, create an instantiated pattern model without data
                else {
                    InstantiatedPatternModel ipm = pf.getPatternFactory().createInstantiatedPatternModel(patternModel, handler);

                    NonClassPatternExecutor patternExecutor = new NonClassPatternExecutor(ipm, getActiveOntology(ontologyUri), getOntologyManager(), IRI.create("http://www.ebi.ac.uk/ontology/webulous#OPPL_pattern"), handler);
                    changes.addAll(patternExecutor.visit(ipm.getPatternModel().getOpplStatement()));


                }
                //print a list of all the changes, then apply them to the ontology
                for (OWLAxiomChange change : changes) {
                    logger.debug(change.toString());
                }
                getOntologyManager().applyChanges(changes);
            }
        } catch (Exception e ) {
            errorCollector.add(e.getMessage());
        }

        return getActiveOntology(ontologyUri);

    }


    public OWLOntologyManager getOntologyManager() {
        if (ontologyManager == null) {
            this.ontologyManager = OWLManager.createOWLOntologyManager();
        }
        return ontologyManager;
    }

    public OWLOntology getActiveOntology(String ontologyUri) throws OWLOntologyCreationException {
        if (activeOntology == null) {
            logger.debug("creating new ontology " + ontologyUri);
            activeOntology = getOntologyManager().createOntology(IRI.create(ontologyUri));
        }
        return activeOntology;
    }


    private void validateAllPatterns(OPPLPatternParser parser, List<PopulousPattern> patterns) {
        for (PopulousPattern pattern : patterns) {
            try {
                parser.parse(pattern.getPatternValue());
            } catch (Exception e) {
                logger.error("Failed to validate pattern: " + pattern.getPatternName(), e);
                throw new RuntimeException("Failed to validate pattern: " + pattern.getPatternName() + ": " + e.getMessage());
            }
        }
    }

    private Map<String, Variable> createOPPLVariableMap(PatternModel patternModel){
        Map<String, Variable> opplVariableMap = new HashMap<String, Variable>();
        for (Variable v : patternModel.getInputVariables()) {
            logger.debug("Loading input variables:" + v.getName());
            opplVariableMap.put(v.getName(), v);
        }
        return opplVariableMap;
    }

    private HashMap<Integer, Map<String, IRI>> createShortFormMapper(List<PopulousDataRestriction> dataRestrictions){
        HashMap<Integer, Map<String, IRI>> sfMapper = new HashMap<Integer, Map<String, IRI>>();
        // for each column, create a new short form mapper for the values that are allowed in that column
        for(PopulousDataRestriction restriction : dataRestrictions){
            Map<String, IRI> labelToUriMap = new HashMap<String, IRI>();
            for (int x = 0; x <restriction.getValues().length; x++) {
                String label = restriction.getValues()[x][0];
                String uri = restriction.getValues()[x][1];
                if (!StringUtils.isBlank(label)) {
                    label = label.trim();
                    label = label.toLowerCase();
                    labelToUriMap.put(label, IRI.create(uri));
                }
            }

            int columnIndex = (restriction.getColumnIndex() - 1);
            sfMapper.put(columnIndex, labelToUriMap);
        }
        return sfMapper;
    }


    private InstantiatedPatternModel processDataRow(String[] row, Map<String, Variable> opplVariableMap, QuickFailRuntimeExceptionHandler handler, PatternModel patternModel) {

        InstantiatedPatternModel ipm = pf.getPatternFactory().createInstantiatedPatternModel(patternModel, handler);

        for (PopulousDataRestriction populousDataRestriction : populousTemplate.getDataRestrictions()) {

            int columnIndex = populousDataRestriction.getColumnIndex() - 1;

            logger.debug("reading col " + columnIndex);
            // see if the row has a value
            if (columnIndex < row.length) {
                String cellValue = row[columnIndex];
                logger.debug("Cell value: "  + cellValue);
                if (!StringUtils.isBlank(cellValue)) {
                    String variable = populousDataRestriction.getVariableName();

                    //check that the variable matches an OPPL pattern input variable
                    // if not we just ignore this column
                    if (opplVariableMap.keySet().contains(variable)) {
                        Variable v = opplVariableMap.get(variable);
                        VariableType type = v.getType();

                        //determine the type of the input variable: OWLClass, OWLIndividual or constant, then instantiate as appropriate
                        if (type.accept(variableVisitor).equals(5)) {
                            logger.debug("instantiating variable as constant:" + opplVariableMap.get(variable).getName() + " to " + cellValue);
                            String [] values = cellValue.split("\\s*\\|\\|\\s*");
                            for (String s : values) {
                                s = s.trim();
                                ipm.instantiate(v, ontologyManager.getOWLDataFactory().getOWLLiteral(s));
                            }
                        }
                        else if (type.accept(variableVisitor).equals(1)) {
                            for (OWLEntity entity : createOWLEntitiesFromValue(cellValue, 1, populousDataRestriction)) {
                                logger.debug("instantiating variable as class:" + opplVariableMap.get(variable).getName() + " to " + entity.getIRI());
                                ipm.instantiate(opplVariableMap.get(variable), entity);
                            }
                        }
                        else if (type.accept(variableVisitor).equals(4)) {
                            for (OWLEntity entity : createOWLEntitiesFromValue(cellValue, 4, populousDataRestriction)) {
                                logger.debug("instantiating variable as class:" + opplVariableMap.get(variable).getName() + " to " + entity.getIRI());
                                ipm.instantiate(opplVariableMap.get(variable), entity);
                            }
                        }
                    }
                }
                else if (populousDataRestriction.isRequired()) {
                    throw new RuntimeException("Missing value for " + populousDataRestriction.getRestrictionName()+ ", which is a required field");
                }
            }
            else {
                throw new RuntimeException("Failed to process row as the number of restricted column index " + columnIndex+ " is greater than the number of columns in the data " + row.length);
            }
        }
        return ipm;
    }

    private Set<OWLEntity> createOWLEntitiesFromValue(String value, int type, PopulousDataRestriction populousDataRestriction) {
        Set<OWLEntity> entities = new HashSet<OWLEntity>();
        if (StringUtils.isNoneBlank(value)) {
            String[] values = value.split("\\s*\\|\\|\\s*");
            for (String s : values) {
                s = s.trim();
                logger.debug("Looking up:" + s);
                OWLEntity entity = getEntityForValue(s, type, populousDataRestriction);
                if (entity !=null) {
                    entities.add(entity) ;
                }
            }
        }
        else {
            if (!StringUtils.isBlank(populousDataRestriction.getDefaultValue())) {
                OWLEntity entity = getEntityForValue(populousDataRestriction.getDefaultValue(), type, populousDataRestriction);
                if (entity !=null) {
                    entities.add(entity) ;
                }
            }
        }
        return entities;
    }

    //get the OWLEntities for data value shortForm, looking first in the list of valid ontology terms for this column, then in all ontologies, then if not found, create a new OWLEntity
    private OWLEntity getEntityForValue(String shortForm, Integer type, PopulousDataRestriction populousDataRestriction) {

        String cleaned = shortForm.trim();
        cleaned = cleaned.toLowerCase();
        int columnIndex = (populousDataRestriction.getColumnIndex() - 1);
        if (shortFromMapper.get(columnIndex).containsKey(cleaned)) {

            if (type == 1) {
                return  ontologyManager.getOWLDataFactory().getOWLClass(shortFromMapper.get(columnIndex).get(cleaned));
            }
            else if (type == 4) {
                return ontologyManager.getOWLDataFactory().getOWLNamedIndividual(shortFromMapper.get(columnIndex).get(cleaned));
            }
        }

//        // then look in the all the ontologies
        for (String s : shortFormProvider.getShortForms()) {
            if (s.toLowerCase().equals(shortForm.toLowerCase())) {
                logger.debug("Entity found:" + s.toLowerCase());
                return shortFormProvider.getEntity(s);
            }
            else if (s.toLowerCase().equals(shortForm.toLowerCase().replaceAll(" ", "_")) ) {
                logger.debug("Entity found:" + s.toLowerCase());
                return shortFormProvider.getEntity(s);
            }
        }

        // finally create a new entity
        return createNewEntity(shortForm, type, populousDataRestriction);
    }


    //create a new OWLEntity (class or individual) for the term newTerm
    private OWLEntity createNewEntity(String shortForm, Integer type, PopulousDataRestriction populousDataRestriction) {

        logger.debug("Creating new term:" + shortForm);

        OWLEntity entity = null;
        if (type == 1) {

            boolean hasRestriction = false;

            OWLEntityCreationSet<OWLClass> ecs = null;
            try {
                logger.info("creating owl class with base URI" + defaultBaseUri.toString());
                ecs = owlEntityFactory.createOWLClass(shortForm, defaultBaseUri);
                if (ecs.getOntologyChanges() != null) {
                    ontologyManager.applyChanges(ecs.getOntologyChanges());
                    shortFormProvider.add(ecs.getOWLEntity());
                    entity = ecs.getOWLEntity();
                }
            } catch (OWLEntityCreationException e) {
                e.printStackTrace();
            }
        }
        else if (type == 4) {
            OWLEntityCreationSet<OWLNamedIndividual> ecs = null;
            try {
                ecs = owlEntityFactory.createOWLIndividual(shortForm, defaultBaseUri);
                if (ecs.getOntologyChanges() != null) {
                    ontologyManager.applyChanges(ecs.getOntologyChanges());
                }
                shortFormProvider.add(ecs.getOWLEntity());
                entity = ecs.getOWLEntity();
            } catch (OWLEntityCreationException e) {
                e.printStackTrace();
            }
        }
        logger.info("new term created with URI " + entity.getIRI());

        return entity;
    }

    private VariableTypeVisitorEx variableVisitor = new VariableTypeVisitorEx ()
    {

        public Object visitCLASSVariableType(CLASSVariableType classVariableType) {
            return 1;
        }

        public Object visitOBJECTPROPERTYVariableType(OBJECTPROPERTYVariableType objectpropertyVariableType) {
            return 2;
        }

        public Object visitDATAPROPERTYVariableType(DATAPROPERTYVariableType datapropertyVariableType) {
            return 3;
        }

        public Object visitINDIVIDUALVariableType(INDIVIDUALVariableType individualVariableType) {
            return 4;
        }

        public Object visitCONSTANTVariableType(CONSTANTVariableType constantVariableType) {
            return 5;
        }

        public Object visitANNOTATIONPROPERTYVariableType(ANNOTATIONPROPERTYVariableType annotationpropertyVariableType) {
            return 6;
        }
    };

}
