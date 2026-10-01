package com.guicedee.activitymaster.documents.graphql;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.IntValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import java.math.BigInteger;
import java.util.Locale;

/** Keeps resource sizes and rating counts at their FSDM 64-bit width. */
final class DocumentLongScalar {
    private DocumentLongScalar() { }
    static GraphQLScalarType create() {
        return GraphQLScalarType.newScalar().name("DocumentLong").coercing(new Coercing<Long, Long>() {
            @Override public Long serialize(Object value, GraphQLContext context, Locale locale) {
                try { return integer(value); }
                catch (IllegalArgumentException failure) { throw new CoercingSerializeException("Expected a signed 64-bit integer"); }
            }
            @Override public Long parseValue(Object value, GraphQLContext context, Locale locale) {
                try { return integer(value); }
                catch (IllegalArgumentException failure) { throw new CoercingParseValueException("Expected a signed 64-bit integer"); }
            }
            @Override public Long parseLiteral(Value<?> value, CoercedVariables variables, GraphQLContext context, Locale locale) {
                if (value instanceof IntValue integer) {
                    try { return integer.getValue().longValueExact(); }
                    catch (ArithmeticException failure) { throw new CoercingParseLiteralException("Integer exceeds signed 64-bit range"); }
                }
                throw new CoercingParseLiteralException("Expected an integer literal");
            }
        }).build();
    }
    private static long integer(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return ((Number) value).longValue();
        if (value instanceof BigInteger integer) {
            try { return integer.longValueExact(); }
            catch (ArithmeticException failure) { throw new IllegalArgumentException(failure); }
        }
        throw new IllegalArgumentException("Expected integer");
    }
}
