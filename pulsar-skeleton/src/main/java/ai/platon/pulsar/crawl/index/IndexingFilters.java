package ai.platon.pulsar.crawl.index;

import ai.platon.pulsar.common.StringUtil;
import ai.platon.pulsar.common.config.ImmutableConfig;
import ai.platon.pulsar.persist.WebPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Creates {@link IndexingFilter} implementing plugins.
 */
public class IndexingFilters {

    public final static Logger LOG = LoggerFactory.getLogger(IndexingFilters.class);

    private ArrayList<IndexingFilter> indexingFilters = new ArrayList<>();

    public IndexingFilters() {
    }

    public IndexingFilters(ImmutableConfig conf) {
        this(Collections.emptyList(), conf);
    }

    public IndexingFilters(List<IndexingFilter> indexingFilters, ImmutableConfig conf) {
        this.indexingFilters.addAll(indexingFilters);
    }

    /**
     * Run all defined filters.
     */
    public IndexDocument filter(IndexDocument doc, String url, WebPage page) {
        for (IndexingFilter indexingFilter : indexingFilters) {
            try {
                doc = indexingFilter.filter(doc, url, page);
                if (doc == null) {
                    break;
                }
            } catch (IndexingException e) {
                LOG.error(StringUtil.stringifyException(e));
                return null;
            }
        }

        return doc;
    }

    @Override
    public String toString() {
        return indexingFilters.stream().map(n -> n.getClass().getName()).collect(Collectors.joining(", "));
    }
}
