/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.percolator;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.sandbox.search.CoveringQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LongValuesSource;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.elasticsearch.Version;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.hash.MurmurHash3;
import org.elasticsearch.common.io.stream.OutputStreamStreamOutput;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xcontent.XContentLocation;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.mapper.BinaryFieldMapper;
import org.elasticsearch.index.mapper.DocumentParserContext;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.KeywordFieldMapper;
import org.elasticsearch.index.mapper.LuceneDocument;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperBuilderContext;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MappingParserContext;
import org.elasticsearch.index.mapper.NumberFieldMapper;
import org.elasticsearch.index.mapper.RangeFieldMapper;
import org.elasticsearch.index.mapper.RangeType;
import org.elasticsearch.index.mapper.SourceValueFetcher;
import org.elasticsearch.index.mapper.TextSearchInfo;
import org.elasticsearch.index.mapper.ValueFetcher;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.BoostingQueryBuilder;
import org.elasticsearch.index.query.ConstantScoreQueryBuilder;
import org.elasticsearch.index.query.DisMaxQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryShardException;
import org.elasticsearch.index.query.Rewriteable;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.elasticsearch.index.query.AbstractQueryBuilder.parseInnerQueryBuilder;

public class PercolatorFieldMapper extends FieldMapper {

    static final Setting<Boolean> INDEX_MAP_UNMAPPED_FIELDS_AS_TEXT_SETTING = Setting.boolSetting(
        "index.percolator.map_unmapped_fields_as_text", false, Setting.Property.IndexScope);
    static final String CONTENT_TYPE = "percolator";

    static final byte FIELD_VALUE_SEPARATOR = 0;  // nul code point
    static final String EXTRACTION_COMPLETE = "complete";
    static final String EXTRACTION_PARTIAL = "partial";
    static final String EXTRACTION_FAILED = "failed";

    static final String EXTRACTED_TERMS_FIELD_NAME = "extracted_terms";
    static final String EXTRACTION_RESULT_FIELD_NAME = "extraction_result";
    static final String QUERY_BUILDER_FIELD_NAME = "query_builder_field";
    static final String RANGE_FIELD_NAME = "range_field";
    static final String MINIMUM_SHOULD_MATCH_FIELD_NAME = "minimum_should_match_field";

    @Override
    public FieldMapper.Builder getMergeBuilder() {
        return new Builder(simpleName(), searchExecutionContext, mapUnmappedFieldsAsText).init(this);
    }

    static class Builder extends FieldMapper.Builder {

        private final Parameter<Map<String, String>> meta = Parameter.metaParam();

        private final Supplier<SearchExecutionContext> searchExecutionContext;
        private final boolean mapUnmappedFieldsAsText;

        Builder(String fieldName, Supplier<SearchExecutionContext> searchExecutionContext, boolean mapUnmappedFieldsAsText) {
            super(fieldName);
            this.searchExecutionContext = searchExecutionContext;
            this.mapUnmappedFieldsAsText = mapUnmappedFieldsAsText;
        }

        @Override
        protected List<Parameter<?>> getParameters() {
            return List.of(meta);
        }

        @Override
        public PercolatorFieldMapper build(MapperBuilderContext context) {
            PercolatorFieldType fieldType = new PercolatorFieldType(context.buildFullName(name), meta.getValue());
            // TODO should percolator even allow multifields?
            MultiFields multiFields = multiFieldsBuilder.build(this, context);
            context = context.createChildContext(name);
            KeywordFieldMapper extractedTermsField = createExtractQueryFieldBuilder(EXTRACTED_TERMS_FIELD_NAME, context);
            fieldType.queryTermsField = extractedTermsField.fieldType();
            KeywordFieldMapper extractionResultField = createExtractQueryFieldBuilder(EXTRACTION_RESULT_FIELD_NAME, context);
            fieldType.extractionResultField = extractionResultField.fieldType();
            BinaryFieldMapper queryBuilderField = createQueryBuilderFieldBuilder(context);
            fieldType.queryBuilderField = queryBuilderField.fieldType();
            // Range field is of type ip, because that matches closest with BinaryRange field. Otherwise we would
            // have to introduce a new field type...
            RangeFieldMapper rangeFieldMapper = createExtractedRangeFieldBuilder(RANGE_FIELD_NAME, RangeType.IP, context);
            fieldType.rangeField = rangeFieldMapper.fieldType();
            NumberFieldMapper minimumShouldMatchFieldMapper = createMinimumShouldMatchField(context);
            fieldType.minimumShouldMatchField = minimumShouldMatchFieldMapper.fieldType();
            fieldType.mapUnmappedFieldsAsText = mapUnmappedFieldsAsText;

            return new PercolatorFieldMapper(name(), fieldType,
                multiFields, copyTo.build(), searchExecutionContext, extractedTermsField,
                extractionResultField, queryBuilderField, rangeFieldMapper, minimumShouldMatchFieldMapper,
                mapUnmappedFieldsAsText);
        }

        static KeywordFieldMapper createExtractQueryFieldBuilder(String name, MapperBuilderContext context) {
            KeywordFieldMapper.Builder queryMetadataFieldBuilder = new KeywordFieldMapper.Builder(name);
            queryMetadataFieldBuilder.docValues(false);
            return queryMetadataFieldBuilder.build(context);
        }

        static BinaryFieldMapper createQueryBuilderFieldBuilder(MapperBuilderContext context) {
            BinaryFieldMapper.Builder builder = new BinaryFieldMapper.Builder(QUERY_BUILDER_FIELD_NAME, true);
            return builder.build(context);
        }

        static RangeFieldMapper createExtractedRangeFieldBuilder(String name, RangeType rangeType, MapperBuilderContext context) {
            RangeFieldMapper.Builder builder = new RangeFieldMapper.Builder(name, rangeType, true);
            // For now no doc values, because in processQuery(...) only the Lucene range fields get added:
            builder.docValues(false);
            return builder.build(context);
        }

        static NumberFieldMapper createMinimumShouldMatchField(MapperBuilderContext context) {
            NumberFieldMapper.Builder builder =
                    NumberFieldMapper.Builder.docValuesOnly(MINIMUM_SHOULD_MATCH_FIELD_NAME, NumberFieldMapper.NumberType.INTEGER);
            return builder.build(context);
        }

    }

    static class TypeParser implements Mapper.TypeParser {

        @Override
        public Builder parse(String name, Map<String, Object> node, MappingParserContext parserContext) throws MapperParsingException {
            return new Builder(name, parserContext.searchExecutionContext(), getMapUnmappedFieldAsText(parserContext.getSettings()));
        }
    }

    private static boolean getMapUnmappedFieldAsText(Settings indexSettings) {
        return INDEX_MAP_UNMAPPED_FIELDS_AS_TEXT_SETTING.get(indexSettings);
    }

    static class PercolatorFieldType extends MappedFieldType {

        MappedFieldType queryTermsField;
        MappedFieldType extractionResultField;
        MappedFieldType queryBuilderField;
        MappedFieldType minimumShouldMatchField;

        RangeFieldMapper.RangeFieldType rangeField;
        boolean mapUnmappedFieldsAsText;

        private PercolatorFieldType(String name, Map<String, String> meta) {
            super(name, false, false, false, TextSearchInfo.NONE, meta);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public Query termQuery(Object value, SearchExecutionContext context) {
            throw new QueryShardException(context, "Percolator fields are not searchable directly, use a percolate query instead");
        }

        @Override
        public ValueFetcher valueFetcher(SearchExecutionContext context, String format) {
            return SourceValueFetcher.identity(name(), context, format);
        }

        Query percolateQuery(String name, PercolateQuery.QueryStore queryStore, List<BytesReference> documents,
                             IndexSearcher searcher, boolean excludeNestedDocuments, Version indexVersion) throws IOException {
            IndexReader indexReader = searcher.getIndexReader();
            Tuple<BooleanQuery, Boolean> t = createCandidateQuery(indexReader, indexVersion);
            Query candidateQuery = t.v1();
            boolean canUseMinimumShouldMatchField = t.v2();

            Query verifiedMatchesQuery;
            // We can only skip the MemoryIndex verification when percolating a single non nested document. We cannot
            // skip MemoryIndex verification when percolating multiple documents, because when terms and
            // ranges are extracted from IndexReader backed by a RamDirectory holding multiple documents we do
            // not know to which document the terms belong too and for certain queries we incorrectly emit candidate
            // matches as actual match.
            if (canUseMinimumShouldMatchField && indexReader.maxDoc() == 1) {
                verifiedMatchesQuery = new TermQuery(new Term(extractionResultField.name(), EXTRACTION_COMPLETE));
            } else {
                verifiedMatchesQuery = new MatchNoDocsQuery("multiple or nested docs or CoveringQuery could not be used");
            }
            Query filter = null;
            if (excludeNestedDocuments) {
                filter = Queries.newNonNestedFilter();
            }
            return new PercolateQuery(name, queryStore, documents, candidateQuery, searcher, filter, verifiedMatchesQuery);
        }

        Tuple<BooleanQuery, Boolean> createCandidateQuery(IndexReader indexReader, Version indexVersion) throws IOException {
            Tuple<List<BytesRef>, Map<String, List<byte[]>>> t = extractTermsAndRanges(indexReader);
            List<BytesRef> extractedTerms = t.v1();
            Map<String, List<byte[]>> encodedPointValuesByField = t.v2();
            // `1 + ` is needed to take into account the EXTRACTION_FAILED should clause
            boolean canUseMinimumShouldMatchField = 1 + extractedTerms.size() + encodedPointValuesByField.size() <=
                BooleanQuery.getMaxClauseCount();

            List<Query> subQueries = new ArrayList<>();
            for (Map.Entry<String, List<byte[]>> entry : encodedPointValuesByField.entrySet()) {
                String rangeFieldName = entry.getKey();
                List<byte[]> encodedPointValues = entry.getValue();
                byte[] min = encodedPointValues.get(0);
                byte[] max = encodedPointValues.get(1);
                Query query = BinaryRange.newIntersectsQuery(rangeField.name(), encodeRange(rangeFieldName, min, max));
                subQueries.add(query);
            }

            BooleanQuery.Builder candidateQuery = new BooleanQuery.Builder();
            if (canUseMinimumShouldMatchField) {
                LongValuesSource valuesSource = LongValuesSource.fromIntField(minimumShouldMatchField.name());
                for (BytesRef extractedTerm : extractedTerms) {
                    subQueries.add(new TermQuery(new Term(queryTermsField.name(), extractedTerm)));
                }
                candidateQuery.add(new CoveringQuery(subQueries, valuesSource), BooleanClause.Occur.SHOULD);
            } else {
                candidateQuery.add(new TermInSetQuery(queryTermsField.name(), extractedTerms), BooleanClause.Occur.SHOULD);
                for (Query subQuery : subQueries) {
                    candidateQuery.add(subQuery, BooleanClause.Occur.SHOULD);
                }
            }
            // include extractionResultField:failed, because docs with this term have no extractedTermsField
            // and otherwise we would fail to return these docs. Docs that failed query term extraction
            // always need to be verified by MemoryIndex:
            candidateQuery.add(new TermQuery(new Term(extractionResultField.name(), EXTRACTION_FAILED)), BooleanClause.Occur.SHOULD);
            return new Tuple<>(candidateQuery.build(), canUseMinimumShouldMatchField);
        }

        // This was extracted the method above, because otherwise it is difficult to test what terms are included in
        // the query in case a CoveringQuery is used (it does not have a getter to retrieve the clauses)
        Tuple<List<BytesRef>, Map<String, List<byte[]>>> extractTermsAndRanges(IndexReader indexReader) throws IOException {
            List<BytesRef> extractedTerms = new ArrayList<>();
            Map<String, List<byte[]>> encodedPointValuesByField = new HashMap<>();

            LeafReader reader = indexReader.leaves().get(0).reader();
            for (FieldInfo info : reader.getFieldInfos()) {
                Terms terms = reader.terms(info.name);
                if (terms != null) {
                    BytesRef fieldBr = new BytesRef(info.name);
                    TermsEnum tenum = terms.iterator();
                    for (BytesRef term = tenum.next(); term != null; term = tenum.next()) {
                        BytesRefBuilder builder = new BytesRefBuilder();
                        builder.append(fieldBr);
                        builder.append(FIELD_VALUE_SEPARATOR);
                        builder.append(term);
                        extractedTerms.add(builder.toBytesRef());
                    }
                }
                if (info.getPointIndexDimensionCount() == 1) { // not != 0 because range fields are not supported
                    PointValues values = reader.getPointValues(info.name);
                    List<byte[]> encodedPointValues = new ArrayList<>();
                    encodedPointValues.add(values.getMinPackedValue().clone());
                    encodedPointValues.add(values.getMaxPackedValue().clone());
                    encodedPointValuesByField.put(info.name, encodedPointValues);
                }
            }
            return new Tuple<>(extractedTerms, encodedPointValuesByField);
        }

    }

    private final Supplier<SearchExecutionContext> searchExecutionContext;
    private final KeywordFieldMapper queryTermsField;
    private final KeywordFieldMapper extractionResultField;
    private final BinaryFieldMapper queryBuilderField;
    private final NumberFieldMapper minimumShouldMatchFieldMapper;
    private final RangeFieldMapper rangeFieldMapper;
    private final boolean mapUnmappedFieldsAsText;

    PercolatorFieldMapper(String simpleName, MappedFieldType mappedFieldType,
                          MultiFields multiFields, CopyTo copyTo, Supplier<SearchExecutionContext> searchExecutionContext,
                          KeywordFieldMapper queryTermsField, KeywordFieldMapper extractionResultField,
                          BinaryFieldMapper queryBuilderField, RangeFieldMapper rangeFieldMapper,
                          NumberFieldMapper minimumShouldMatchFieldMapper, boolean mapUnmappedFieldsAsText) {
        super(simpleName, mappedFieldType, multiFields, copyTo);
        this.searchExecutionContext = searchExecutionContext;
        this.queryTermsField = queryTermsField;
        this.extractionResultField = extractionResultField;
        this.queryBuilderField = queryBuilderField;
        this.minimumShouldMatchFieldMapper = minimumShouldMatchFieldMapper;
        this.rangeFieldMapper = rangeFieldMapper;
        this.mapUnmappedFieldsAsText = mapUnmappedFieldsAsText;
    }

    @Override
    public void parse(DocumentParserContext context) throws IOException {
        SearchExecutionContext searchExecutionContext = this.searchExecutionContext.get();
        if (context.doc().getField(queryBuilderField.name()) != null) {
            // If a percolator query has been defined in an array object then multiple percolator queries
            // could be provided. In order to prevent this we fail if we try to parse more than one query
            // for the current document.
            throw new IllegalArgumentException("a document can only contain one percolator query");
        }

        configureContext(searchExecutionContext, isMapUnmappedFieldAsText());

        XContentParser parser = context.parser();
        QueryBuilder queryBuilder = parseQueryBuilder(
                parser, parser.getTokenLocation()
        );
        verifyQuery(queryBuilder);
        // Fetching of terms, shapes and indexed scripts happen during this rewrite:
        PlainActionFuture<QueryBuilder> future = new PlainActionFuture<>();
        Rewriteable.rewriteAndFetch(queryBuilder, searchExecutionContext, future);
        queryBuilder = future.actionGet();

        Version indexVersion = context.indexSettings().getIndexVersionCreated();
        createQueryBuilderField(indexVersion, queryBuilderField, queryBuilder, context);

        QueryBuilder queryBuilderForProcessing = queryBuilder.rewrite(new SearchExecutionContext(searchExecutionContext));
        Query query = queryBuilderForProcessing.toQuery(searchExecutionContext);
        processQuery(query, context);
    }

    static void createQueryBuilderField(Version indexVersion, BinaryFieldMapper qbField,
                                        QueryBuilder queryBuilder, DocumentParserContext context) throws IOException {
        try (ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            try (OutputStreamStreamOutput out  = new OutputStreamStreamOutput(stream)) {
                out.setVersion(indexVersion);
                out.writeNamedWriteable(queryBuilder);
                qbField.indexValue(context, stream.toByteArray());
            }
        }
    }

    private static final FieldType INDEXED_KEYWORD = new FieldType();
    static {
        INDEXED_KEYWORD.setTokenized(false);
        INDEXED_KEYWORD.setOmitNorms(true);
        INDEXED_KEYWORD.setIndexOptions(IndexOptions.DOCS);
        INDEXED_KEYWORD.freeze();
    }

    void processQuery(Query query, DocumentParserContext context) {
        LuceneDocument doc = context.doc();
        PercolatorFieldType pft = (PercolatorFieldType) this.fieldType();
        QueryAnalyzer.Result result;
        result = QueryAnalyzer.analyze(query);
        if (result == QueryAnalyzer.Result.UNKNOWN) {
            doc.add(new Field(pft.extractionResultField.name(), EXTRACTION_FAILED, INDEXED_KEYWORD));
            return;
        }
        for (QueryAnalyzer.QueryExtraction extraction : result.extractions) {
            if (extraction.term != null) {
                BytesRefBuilder builder = new BytesRefBuilder();
                builder.append(new BytesRef(extraction.field()));
                builder.append(FIELD_VALUE_SEPARATOR);
                builder.append(extraction.bytes());
                doc.add(new Field(queryTermsField.name(), builder.toBytesRef(), INDEXED_KEYWORD));
            } else if (extraction.range != null) {
                byte[] min = extraction.range.lowerPoint;
                byte[] max = extraction.range.upperPoint;
                doc.add(new BinaryRange(rangeFieldMapper.name(), encodeRange(extraction.range.fieldName, min, max)));
            }
        }

        if (result.matchAllDocs) {
            doc.add(new Field(extractionResultField.name(), EXTRACTION_FAILED, INDEXED_KEYWORD));
            if (result.verified) {
                doc.add(new Field(extractionResultField.name(), EXTRACTION_COMPLETE, INDEXED_KEYWORD));
            }
        } else if (result.verified) {
            doc.add(new Field(extractionResultField.name(), EXTRACTION_COMPLETE, INDEXED_KEYWORD));
        } else {
            doc.add(new Field(extractionResultField.name(), EXTRACTION_PARTIAL, INDEXED_KEYWORD));
        }

        context.addToFieldNames(fieldType().name());
        doc.add(new NumericDocValuesField(minimumShouldMatchFieldMapper.name(), result.minimumShouldMatch));
    }

    static void configureContext(SearchExecutionContext context, boolean mapUnmappedFieldsAsString) {
        // This means that fields in the query need to exist in the mapping prior to registering this query
        // The reason that this is required, is that if a field doesn't exist then the query assumes defaults, which may be undesired.
        //
        // Even worse when fields mentioned in percolator queries do go added to map after the queries have been registered
        // then the percolator queries don't work as expected any more.
        //
        // Query parsing can't introduce new fields in mappings (which happens when registering a percolator query),
        // because field type can't be inferred from queries (like document do) so the best option here is to disallow
        // the usage of unmapped fields in percolator queries to avoid unexpected behaviour
        //
        // if index.percolator.map_unmapped_fields_as_string is set to true, query can contain unmapped fields which will be mapped
        // as an analyzed string.
        context.setAllowUnmappedFields(false);
        context.setMapUnmappedFieldAsString(mapUnmappedFieldsAsString);
    }

    private static QueryBuilder parseQueryBuilder(XContentParser parser, XContentLocation location) {
        try {
            return parseInnerQueryBuilder(parser);
        } catch (IOException e) {
            throw new ParsingException(location, "Failed to parse", e);
        }
    }

    @Override
    public Iterator<Mapper> iterator() {
        return Arrays.<Mapper>asList(
                queryTermsField, extractionResultField, queryBuilderField, minimumShouldMatchFieldMapper, rangeFieldMapper
        ).iterator();
    }

    @Override
    protected void parseCreateField(DocumentParserContext context) {
        throw new UnsupportedOperationException("should not be invoked");
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    boolean isMapUnmappedFieldAsText() {
        return mapUnmappedFieldsAsText;
    }

    /**
     * Fails if a percolator contains an unsupported query. The following queries are not supported:
     * 1) a has_child query
     * 2) a has_parent query
     */
    static void verifyQuery(QueryBuilder queryBuilder) {
        if (queryBuilder.getName().equals("has_child")) {
            throw new IllegalArgumentException("the [has_child] query is unsupported inside a percolator query");
        } else if (queryBuilder.getName().equals("has_parent")) {
            throw new IllegalArgumentException("the [has_parent] query is unsupported inside a percolator query");
        } else if (queryBuilder instanceof BoolQueryBuilder) {
            BoolQueryBuilder boolQueryBuilder = (BoolQueryBuilder) queryBuilder;
            List<QueryBuilder> clauses = new ArrayList<>();
            clauses.addAll(boolQueryBuilder.filter());
            clauses.addAll(boolQueryBuilder.must());
            clauses.addAll(boolQueryBuilder.mustNot());
            clauses.addAll(boolQueryBuilder.should());
            for (QueryBuilder clause : clauses) {
                verifyQuery(clause);
            }
        } else if (queryBuilder instanceof ConstantScoreQueryBuilder) {
            verifyQuery(((ConstantScoreQueryBuilder) queryBuilder).innerQuery());
        } else if (queryBuilder instanceof FunctionScoreQueryBuilder) {
            verifyQuery(((FunctionScoreQueryBuilder) queryBuilder).query());
        } else if (queryBuilder instanceof BoostingQueryBuilder) {
            verifyQuery(((BoostingQueryBuilder) queryBuilder).negativeQuery());
            verifyQuery(((BoostingQueryBuilder) queryBuilder).positiveQuery());
        } else if (queryBuilder instanceof DisMaxQueryBuilder) {
            DisMaxQueryBuilder disMaxQueryBuilder = (DisMaxQueryBuilder) queryBuilder;
            for (QueryBuilder innerQueryBuilder : disMaxQueryBuilder.innerQueries()) {
                verifyQuery(innerQueryBuilder);
            }
        }
    }

    static byte[] encodeRange(String rangeFieldName, byte[] minEncoded, byte[] maxEncoded) {
        assert minEncoded.length == maxEncoded.length;
        byte[] bytes = new byte[BinaryRange.BYTES * 2];

        // First compute hash for field name and write the full hash into the byte array
        BytesRef fieldAsBytesRef = new BytesRef(rangeFieldName);
        MurmurHash3.Hash128 hash = new MurmurHash3.Hash128();
        MurmurHash3.hash128(fieldAsBytesRef.bytes, fieldAsBytesRef.offset, fieldAsBytesRef.length, 0, hash);
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.putLong(hash.h1).putLong(hash.h2).putLong(hash.h1).putLong(hash.h2);
        assert bb.position() == bb.limit();

        // Secondly, overwrite the min and max encoded values in the byte array
        // This way we are able to reuse as much as possible from the hash for any range type.
        int offset = BinaryRange.BYTES - minEncoded.length;
        System.arraycopy(minEncoded, 0, bytes, offset, minEncoded.length);
        System.arraycopy(maxEncoded, 0, bytes, BinaryRange.BYTES + offset, maxEncoded.length);
        return bytes;
    }
}
