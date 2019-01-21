package fun.platonic.pulsar;

import fun.platonic.pulsar.common.UrlUtil;
import fun.platonic.pulsar.common.config.ImmutableConfig;
import fun.platonic.pulsar.common.config.MutableConfig;
import fun.platonic.pulsar.common.options.LoadOptions;
import fun.platonic.pulsar.crawl.component.BatchFetchComponent;
import fun.platonic.pulsar.crawl.component.InjectComponent;
import fun.platonic.pulsar.crawl.component.LoadComponent;
import fun.platonic.pulsar.crawl.component.ParseComponent;
import fun.platonic.pulsar.crawl.filter.UrlNormalizers;
import fun.platonic.pulsar.crawl.parse.html.JsoupParser;
import fun.platonic.pulsar.dom.FeaturedDocument;
import fun.platonic.pulsar.net.SeleniumEngine;
import fun.platonic.pulsar.persist.WebDb;
import fun.platonic.pulsar.persist.WebPage;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

import static fun.platonic.pulsar.common.config.CapabilityTypes.APPLICATION_CONTEXT_CONFIG_LOCATION;
import static fun.platonic.pulsar.common.config.PulsarConstants.APP_CONTEXT_CONFIG_LOCATION;

public class Pulsar implements AutoCloseable {

    private final ImmutableConfig immutableConfig;
    private final WebDb webDb;
    private final InjectComponent injectComponent;
    private final LoadComponent loadComponent;
    private final UrlNormalizers urlNormalizers;
    private MutableConfig defaultMutableConfig;
    private AtomicBoolean isClosed = new AtomicBoolean(false);

    public Pulsar() {
        this(new ClassPathXmlApplicationContext(
                System.getProperty(APPLICATION_CONTEXT_CONFIG_LOCATION, APP_CONTEXT_CONFIG_LOCATION)));
    }

    public Pulsar(String appConfigLocation) {
        this(new ClassPathXmlApplicationContext(appConfigLocation));
    }

    public Pulsar(ConfigurableApplicationContext applicationContext) {
        this.immutableConfig = applicationContext.getBean(MutableConfig.class);

        this.webDb = applicationContext.getBean(WebDb.class);
        this.injectComponent = applicationContext.getBean(InjectComponent.class);
        this.loadComponent = applicationContext.getBean(LoadComponent.class);
        this.urlNormalizers = applicationContext.getBean(UrlNormalizers.class);

        this.defaultMutableConfig = new MutableConfig(immutableConfig.unbox());
    }

    public Pulsar(
            InjectComponent injectComponent,
            LoadComponent loadComponent,
            UrlNormalizers urlNormalizers,
            ImmutableConfig immutableConfig) {
        this.webDb = injectComponent.getWebDb();

        this.injectComponent = injectComponent;
        this.loadComponent = loadComponent;
        this.urlNormalizers = urlNormalizers;
        this.immutableConfig = immutableConfig;
    }

    public ImmutableConfig getImmutableConfig() {
        return immutableConfig;
    }

    public WebDb getWebDb() {
        return webDb;
    }

    public InjectComponent getInjectComponent() {
        return injectComponent;
    }

    public LoadComponent getLoadComponent() {
        return loadComponent;
    }

    public ParseComponent getParseComponent() {
        return loadComponent.getParseComponent();
    }

    public BatchFetchComponent getFetchComponent() {
        return loadComponent.getFetchComponent();
    }

    @Nullable
    public String normalize(String url) {
        return urlNormalizers.normalize(url);
    }

    /**
     * Inject a url
     *
     * @param configuredUrl The url followed by config options
     * @return The web page created
     */
    @Nonnull
    public WebPage inject(String configuredUrl) {
        return injectComponent.inject(UrlUtil.splitUrlArgs(configuredUrl));
    }

    @Nullable
    public WebPage get(String url) {
        return webDb.get(url);
    }

    public WebPage getOrNil(String url) {
        return webDb.getOrNil(url);
    }

    public Iterator<WebPage> scan(String urlBase) {
        return webDb.scan(urlBase);
    }

    public Iterator<WebPage> scan(String urlBase, String[] fields) {
        return webDb.scan(urlBase, fields);
    }

    /**
     * Load a url, options can be specified following the url, see {@link LoadOptions} for all options
     *
     * @param configuredUrl The url followed by options
     * @return The WebPage. If there is no web page at local storage nor remote location, {@link WebPage#NIL} is returned
     */
    @Nonnull
    public WebPage load(String configuredUrl) {
        Pair<String, String> urlAndOptions = UrlUtil.splitUrlArgs(configuredUrl);

        LoadOptions options = LoadOptions.parse(urlAndOptions.getValue(), defaultMutableConfig);
        options.setMutableConfig(defaultMutableConfig);

        return loadComponent.load(urlAndOptions.getKey(), options);
    }

    /**
     * Load a url with specified options, see {@link LoadOptions} for all options
     *
     * @param url     The url to load
     * @param options The options
     * @return The WebPage. If there is no web page at local storage nor remote location, {@link WebPage#NIL} is returned
     */
    @Nonnull
    public WebPage load(String url, LoadOptions options) {
        if (options.getMutableConfig() == null) {
            options.setMutableConfig(defaultMutableConfig);
        }
        return loadComponent.load(url, options);
    }

    /**
     * Load a batch of urls with the specified options.
     * <p>
     * If the option indicates prefer parallel, urls are fetched in a parallel manner whenever applicable.
     * If the batch is too large, only a random part of the urls is fetched immediately, all the rest urls are put into
     * a pending fetch list and will be fetched in background later.
     * <p>
     * If a page does not exists neither in local storage nor at the given remote location, {@link WebPage#NIL} is returned
     *
     * @param urls The urls to load
     * @return Pages for all urls.
     */
    public Collection<WebPage> loadAll(Iterable<String> urls) {
        return loadAll(urls, new LoadOptions());
    }

    /**
     * Load a batch of urls with the specified options.
     * <p>
     * If the option indicates prefer parallel, urls are fetched in a parallel manner whenever applicable.
     * If the batch is too large, only a random part of the urls is fetched immediately, all the rest urls are put into
     * a pending fetch list and will be fetched in background later.
     * <p>
     * If a page does not exists neither in local storage nor at the given remote location, {@link WebPage#NIL} is returned
     *
     * @param urls    The urls to load
     * @param options The options
     * @return Pages for all urls.
     */
    public Collection<WebPage> loadAll(Iterable<String> urls, LoadOptions options) {
        if (options.getMutableConfig() == null) {
            options.setMutableConfig(defaultMutableConfig);
        }
        return loadComponent.loadAll(urls, options);
    }

    /**
     * Load a batch of urls with the specified options.
     * <p>
     * Urls are fetched in a parallel manner whenever applicable.
     * If the batch is too large, only a random part of the urls is fetched immediately, all the rest urls are put into
     * a pending fetch list and will be fetched in background later.
     * <p>
     * If a page does not exists neither in local storage nor at the given remote location, {@link WebPage#NIL} is returned
     *
     * @param urls The urls to load
     * @return Pages for all urls.
     */
    public Collection<WebPage> parallelLoadAll(Iterable<String> urls) {
        return parallelLoadAll(urls, new LoadOptions());
    }

    /**
     * Load a batch of urls with the specified options.
     * <p>
     * Urls are fetched in a parallel manner whenever applicable.
     * If the batch is too large, only a random part of the urls is fetched immediately, all the rest urls are put into
     * a pending fetch list and will be fetched in background later.
     * <p>
     * If a page does not exists neither in local storage nor at the given remote location, {@link WebPage#NIL} is returned
     *
     * @param urls    The urls to load
     * @param options The options
     * @return Pages for all urls.
     */
    public Collection<WebPage> parallelLoadAll(Iterable<String> urls, LoadOptions options) {
        if (options.getMutableConfig() == null) {
            options.setMutableConfig(defaultMutableConfig);
        }
        return loadComponent.parallelLoadAll(urls, options);
    }

    /**
     * Parse the WebPage using Jsoup
     */
    @Nonnull
    public FeaturedDocument parse(WebPage page) {
        JsoupParser parser = new JsoupParser(page, immutableConfig);
        return new FeaturedDocument(parser.parse());
    }

    @Nonnull
    public FeaturedDocument parse(WebPage page, MutableConfig mutableConfig) {
        JsoupParser parser = new JsoupParser(page, mutableConfig);
        return new FeaturedDocument(parser.parse());
    }

    /**
     * @deprecated Use {#persist} instead
     * */
    public void save(WebPage page) {
        webDb.put(page.getUrl(), page);
    }

    public void persist(WebPage page) {
        webDb.put(page.getUrl(), page);
    }

    public void delete(String url) {
        webDb.delete(url);
    }

    public void delete(WebPage page) {
        webDb.delete(page.getUrl());
    }

    public void flush() {
        webDb.flush();
    }

    @Override
    public void close() {
        if (isClosed.getAndSet(true)) {
            return;
        }

        SeleniumEngine.getInstance(immutableConfig).close();
        injectComponent.close();
        webDb.close();
    }
}
