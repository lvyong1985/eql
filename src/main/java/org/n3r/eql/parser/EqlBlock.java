package org.n3r.eql.parser;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.n3r.eql.cache.EqlCacheKey;
import org.n3r.eql.cache.EqlCacheProvider;
import org.n3r.eql.cache.EqlCacheSettings;
import org.n3r.eql.config.EqlConfigDecorator;
import org.n3r.eql.impl.EqlUniqueSqlId;
import org.n3r.eql.map.EqlRun;
import org.n3r.eql.param.EqlParamsParser;
import org.n3r.eql.util.EqlUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class EqlBlock {
    private int startLineNo;
    private Map<String, String> options = Maps.newHashMap();
    private Class<?> returnType;
    private String onerr;
    private String split;

    private List<Sql> sqls = Lists.newArrayList();
    private Collection<String> sqlLines;
    private EqlUniqueSqlId uniqueSqlId;
    private EqlCacheProvider cacheProvider;
    private boolean ref; // used to be referred as a partial common sql

    public EqlBlock(String sqlClassPath, String sqlId, String options, int startLineNo) {
        this.uniqueSqlId = new EqlUniqueSqlId(sqlClassPath, sqlId);
        this.startLineNo = startLineNo;
        this.options = BlockOptionsParser.parseOptions(options);

        initSomeOptions();
    }

    public EqlBlock() {
        this.uniqueSqlId = new EqlUniqueSqlId("<DirectSql>", "<Auto>");
    }

    private void initSomeOptions() {
        onerr = options.get("onerr");
        returnType = EqlUtils.tryLoadClass(options.get("returnType"));

        split = options.get("split");
        if (Strings.isNullOrEmpty(split)) split = ";";

        ref = options.containsKey("ref");

        initEqlCache(options.containsKey("cache"), options.get("cacheModel"));
    }

    private void initEqlCache(boolean useCache, String cacheModel) {
        if (Strings.isNullOrEmpty(cacheModel) && !useCache) return;

        cacheProvider = EqlCacheSettings.getCacheProvider(uniqueSqlId,
                Objects.firstNonNull(cacheModel, EqlCacheSettings.defaultCacheModel));
    }

    public List<Sql> getSqls() {
        return sqls;
    }

    public List<EqlRun> createEqlRuns(EqlConfigDecorator eqlConfig, Map<String, Object> executionContext,
                                      Object[] params, Object[] dynamics, String[] directSqls) {
        return directSqls.length == 0
                ? createEqlRunsByEqls(eqlConfig, executionContext, params, dynamics)
                : createSqlSubsByDirectSqls(eqlConfig, executionContext, params, dynamics, directSqls);
    }

    public List<EqlRun> createEqlRunsByEqls(EqlConfigDecorator eqlConfig, Map<String, Object> executionContext,
                                            Object[] params, Object[] dynamics) {
        Object paramBean = EqlUtils.createSingleBean(params);

        List<EqlRun> eqlRuns = Lists.newArrayList();
        EqlRun lastSelectSql = null;
        for (Sql sql : sqls) {
            EqlRun eqlRun = newEqlRun(eqlConfig, executionContext, params, dynamics, paramBean, eqlRuns);

            String sqlStr = sql.evalSql(eqlRun);
            sqlStr = EqlUtils.autoTrimLastUnusedPart(sqlStr);

            addEqlRun(eqlRun, sqlStr);


            if (eqlRun.getSqlType() == EqlRun.EqlType.SELECT) lastSelectSql = eqlRun;
        }

        if (lastSelectSql != null) lastSelectSql.setLastSelectSql(true);

        return eqlRuns;
    }


    private void addEqlRun(EqlRun eqlRun, String sqlStr) {
        new EqlParamsParser(eqlRun).parseParams(sqlStr);
        new DynamicReplacer().replaceDynamics(eqlRun);

        eqlRun.createPrintSql();
    }

    public List<EqlRun> createSqlSubsByDirectSqls(EqlConfigDecorator eqlConfig, Map<String, Object> executionContext,
                                                  Object[] params, Object[] dynamics, String[] sqls) {
        Object paramBean = EqlUtils.createSingleBean(params);

        List<EqlRun> eqlRuns = Lists.newArrayList();
        EqlRun lastSelectSql = null;
        for (String sql : sqls) {
            EqlRun eqlRun = newEqlRun(eqlConfig, executionContext, params, dynamics, paramBean, eqlRuns);

            addEqlRun(eqlRun, sql);

            if (eqlRun.getSqlType() == EqlRun.EqlType.SELECT) lastSelectSql = eqlRun;
        }

        if (lastSelectSql != null) lastSelectSql.setLastSelectSql(true);

        return eqlRuns;
    }


    private EqlRun newEqlRun(EqlConfigDecorator eqlConfig, Map<String, Object> executionContext, Object[] params,
                             Object[] dynamics, Object paramBean, List<EqlRun> eqlRuns) {
        EqlRun eqlRun = new EqlRun();
        eqlRuns.add(eqlRun);

        eqlRun.setEqlConfig(eqlConfig);
        eqlRun.setExecutionContext(executionContext);
        eqlRun.setParams(params);
        eqlRun.setDynamics(dynamics);
        eqlRun.setParamBean(paramBean);
        eqlRun.setEqlBlock(this);
        return eqlRun;
    }

    public boolean isOnerrResume() {
        return "resume".equalsIgnoreCase(onerr);
    }

    public Class<?> getReturnType() {
        return returnType;
    }

    public Map<String, String> getOptions() {
        return options;
    }

    public void setSqls(List<Sql> sqls) {
        this.sqls = sqls;
    }

    public Collection<? extends String> getSqlLines() {
        return sqlLines;
    }

    public void setSqlLines(List<String> sqlLines) {
        this.sqlLines = sqlLines;
    }

    public String getSplit() {
        return split;
    }

    public void tryParseSqls() {
        for (Sql sql : sqls) {
            if (sql instanceof DelaySql) {
                ((DelaySql) sql).parseSql();
            }
        }
    }

    public EqlUniqueSqlId getUniqueSqlId() {
        return uniqueSqlId;
    }

    public String getUniqueSqlIdStr() {
        return uniqueSqlId.getSqlClassPath() + ":" + uniqueSqlId.getSqlId();
    }

    public String getSqlId() {
        return uniqueSqlId.getSqlId();
    }

    public Optional<Object> getCachedResult(Object[] params, Object[] dynamics) {
        if (cacheProvider == null) return null;

        return cacheProvider.getCache(new EqlCacheKey(uniqueSqlId, params, dynamics));
    }

    public void cacheResult(EqlRun currRun) {
        if (cacheProvider == null) return;
        if (!currRun.isLastSelectSql()) return;

        cacheProvider.setCache(new EqlCacheKey(uniqueSqlId, currRun.getParams(), currRun.getDynamics()),
                currRun.getResult());
    }

    public boolean isRef() {
        return ref;
    }
}
