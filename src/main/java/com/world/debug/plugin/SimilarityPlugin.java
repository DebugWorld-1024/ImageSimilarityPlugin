package com.world.debug.plugin;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.store.ByteArrayDataInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.script.ScoreScript;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptEngine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;


public class SimilarityPlugin extends Plugin implements ScriptPlugin {
    private static final Logger logger = LogManager.getLogger(SimilarityPlugin.class.getName());

    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        return new SimilarityEngine();
    }

    private static class SimilarityEngine implements ScriptEngine {
        // 通过这两个字段判断是否使用该插件
        private final String _SOURCE_VALUE = "DebugWorld";
        private final String _LANG_VALUE = "ImageSimilarity";

        @Override
        public String getType() {
            return _LANG_VALUE;
        }

        @Override
        public <T> T compile(String scriptName,
                             String scriptSource,
                             ScriptContext<T> context,
                             Map<String, String> params) {
            if (!context.equals(ScoreScript.CONTEXT)) {
                throw new IllegalArgumentException(getType()
                        + " scripts cannot be used for context ["
                        + context.name + "]");
            }
            if (!_SOURCE_VALUE.equals(scriptSource)) {
                throw new IllegalArgumentException("Unknown script name "
                        + scriptSource);
            }

            ScoreScript.Factory factory = (p, lookup) -> new ScoreScript.LeafFactory() {
                String field = p.get("field").toString();
                List<Double> inputVector = (List<Double>) p.get("feature");
//                double[] inputVector = Util.convertBase64ToArray((String) sourceFeature);

                @Override
                public ScoreScript newInstance(LeafReaderContext context) throws IOException {
                    return new ScoreScript(p, lookup, context) {
                        Boolean is_value = false;
                        // 二进制形式加载es数据，比常规加载方式快很多
                        BinaryDocValues accessor = context.reader().getBinaryDocValues(field);

                        @Override
                        public void setDocument(int docId) {
                            try {
                                accessor.advanceExact(docId);
                                is_value = true;
                            } catch (Exception e) {
                                logger.error(e);
                                is_value = false;
                            }
                        }

                        @Override
                        public double execute() {
                            if (!is_value) return 0.0d;

                            final int inputVectorSize = inputVector.size();
                            final byte[] bytes;
                            try {
                                bytes = accessor.binaryValue().bytes;
                            } catch (Exception e) {
                                return 0.0d;
                            }
                            final ByteArrayDataInput docVectorBytes = new ByteArrayDataInput(bytes);
                            docVectorBytes.readVInt();
                            final int docVectorSize = docVectorBytes.readVInt();
                            final int position = docVectorBytes.getPosition();
                            final DoubleBuffer doubleBuffer = ByteBuffer.wrap(bytes, position, docVectorSize).asDoubleBuffer();
                            final double[] docVector = new double[inputVectorSize];
                            doubleBuffer.get(docVector);
                            // 常规加载es数据方式
                            // List<Double> docVector = (List<Double>) lookup.source().get(field);
                            return EuclideanMetric.getScore(inputVector, docVector);
//                            return Math.random();
                        }
                    };
                }

                @Override
                public boolean needs_score() {
                    return false;
                }
            };
            return context.factoryClazz.cast(factory);
        }

        @Override
        public void close() throws IOException {

        }
    }
}

