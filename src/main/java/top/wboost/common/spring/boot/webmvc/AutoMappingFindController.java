package top.wboost.common.spring.boot.webmvc;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import io.swagger.annotations.ApiParam;
import io.swagger.models.Swagger;
import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.UriComponents;
import springfox.documentation.annotations.ApiIgnore;
import springfox.documentation.service.Documentation;
import springfox.documentation.spring.web.DocumentationCache;
import springfox.documentation.spring.web.PropertySourcedRequestMappingHandlerMapping;
import springfox.documentation.spring.web.json.Json;
import springfox.documentation.spring.web.json.JsonSerializer;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.mappers.ServiceModelToSwagger2Mapper;
import springfox.documentation.swagger2.web.HostNameProvider;
import springfox.documentation.swagger2.web.Swagger2Controller;
import top.wboost.common.annotation.Explain;
import top.wboost.common.annotation.parameter.NotEmpty;
import top.wboost.common.base.entity.ResultEntity;
import top.wboost.common.spring.boot.webmvc.annotation.ApiVersion;
import top.wboost.common.spring.boot.webmvc.annotation.GlobalForApiConfig;
import top.wboost.common.system.code.SystemCode;
import top.wboost.common.util.ReflectUtil;
import top.wboost.common.utils.web.interfaces.context.EzWebApplicationListener;
import top.wboost.common.utils.web.utils.JSONConfig;
import top.wboost.common.utils.web.utils.JSONObjectUtil;
import top.wboost.common.utils.web.utils.SpringBeanUtil;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.Map.Entry;

@RestController
@RequestMapping("/webmvc/mapping")
@ApiIgnore
public class AutoMappingFindController implements InitializingBean, EzWebApplicationListener {

    private static ParameterNameDiscoverer parameterNameDiscoverer = new LocalVariableTableParameterNameDiscoverer();
    ObjectMapper objectMapper = null;
    HandlerMethod method = null;
    Method componentsFromMethod = ReflectUtil.findMethod(HostNameProvider.class, "componentsFrom",
            HttpServletRequest.class, String.class);
    Object handler = null;
    JSONConfig jsonConfig = new JSONConfig();
    Object mappingRegistry = null;
    boolean init = false;
    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;
    private Swagger2Controller swCon;
    private MultiValueMap<String, RequestMappingInfo> urlLookup;
    private Map<RequestMappingInfo, HandlerMethod> mappingLookup;
    private String hostNameOverride = null;
    private DocumentationCache documentationCache = null;
    private ServiceModelToSwagger2Mapper mapper = null;
    private JsonSerializer jsonSerializer = null;

    {
        componentsFromMethod.setAccessible(true);
        jsonConfig.setDisableCircularReferenceDetect(true);
    }

    @GetMapping
    @Explain(value = "查询所有接口")
    public ResultEntity getAllMapping() {
        Map<RequestMappingInfo, HandlerMethod> map = requestMappingHandlerMapping.getHandlerMethods();
        List<ReturnInfo> result = new ArrayList<>();
        map.forEach((mappingInfo, handlerMethod) -> {
            result.add(new ReturnInfo(mappingInfo, handlerMethod));
        });
        return ResultEntity.success(SystemCode.QUERY_OK).setData(result)
                .setFilterNames("handlerMethod", "requestMappingInfo").build();
    }

    /*@Data
    public static class RequestMappingInfoSimple {
        private ConsumesRequestCondition  consumesRequestCondition ;
        private HeadersRequestCondition  headersRequestCondition ;
        private RequestMethodsRequestCondition  methodsCondition ;
        private ParamsRequestCondition paramsCondition;
        private PatternsRequestCondition  patternsCondition;
        private ProducesRequestCondition producesCondition;
    }*/

    @SuppressWarnings("unchecked")
    @GetMapping("docs")
    @Explain(value = "查询所有接口")
    public ResultEntity getAllMappingBySwagger(@RequestParam(value = "group", required = false) String swaggerGroup,
            HttpServletRequest servletRequest) {
        ResponseEntity<Json> response = null;
        JSONObject json = null;
        try {
            Swagger swagger = getDocumentation(swaggerGroup, servletRequest);
            json = JSONObject.parseObject(JSONObjectUtil.toJSONString(swagger, jsonConfig, "vendorExtensions",
                    "operations", "operationMap", "empty"));
            JSONArray simpleApis = new JSONArray();
            json.getJSONObject("paths").forEach((path, jo) -> {
                JSONObject paths = (JSONObject) jo;
                List<RequestMappingInfo> infos = urlLookup.get(path);
                if (infos.size() > 0) {
                    paths.forEach((method, values) -> {
                        JSONObject valuesObj = (JSONObject) values;
                        RequestMethod m = RequestMethod.valueOf(method.toUpperCase());
                        for (RequestMappingInfo info : infos) {
                            if (info.getMethodsCondition().getMethods().contains(m)) {
                                HandlerMethod handlerMethod = mappingLookup.get(info);
                                if (handlerMethod != null) {
                                    ApiVersion version = handlerMethod.getMethodAnnotation(ApiVersion.class);
                                    String versionStr;
                                    if (version == null) {
                                        versionStr = GlobalForApiConfig.DEFAULT_VERSION;
                                    } else {
                                        versionStr = version.value();
                                    }
                                    valuesObj.put("version", versionStr);
                                    JSONObject simpleApi = new JSONObject();
                                    simpleApi.put("description", valuesObj.getString("description"));
                                }
                                break;
                            }
                        }
                    });
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return ResultEntity.success(SystemCode.QUERY_OK).setData(json).build();
    }

    @GetMapping("docs/simple")
    @Explain(value = "查询所有接口")
    public ResultEntity getAllMappingBySwaggerSimple(@RequestParam(value = "group", required = false) String swaggerGroup,
                                                     HttpServletRequest servletRequest) {
        ResultEntity allMappingBySwagger = getAllMappingBySwagger(swaggerGroup, servletRequest);
        return allMappingBySwagger.setFilterNames("responses", "parameters");
    }

    @PostMapping("docs/detail")
    @Explain(value = "查询接口详情")
    public ResultEntity getAllMappingBySwaggerSimple(@NotEmpty String path, @NotEmpty String method, @RequestParam(value = "group", required = false) String swaggerGroup,
                                                     HttpServletRequest servletRequest) {
        ResultEntity allMappingBySwagger = getAllMappingBySwagger(swaggerGroup, servletRequest);
        JSONObject jo = (JSONObject) allMappingBySwagger.getData();
        JSONObject paths = jo.getJSONObject("paths");
        JSONObject pathObj = paths.getJSONObject(path);
        JSONObject methodObj = pathObj.getJSONObject(method);
        return ResultEntity.success(SystemCode.QUERY_OK).setData(methodObj).build();
    }

    @GetMapping("/url")
    @Explain(value = "查询对应路径接口")
    public ResultEntity getByUrl(String url) {
        List<RequestMappingInfo> list;
        if (urlLookup != null) {
            list = urlLookup.get(url);
        } else {
            list = new ArrayList<>();
            for (Entry<RequestMappingInfo, HandlerMethod> entry : requestMappingHandlerMapping.getHandlerMethods()
                    .entrySet()) {
                RequestMappingInfo mappingInfo = entry.getKey();
                if (mappingInfo.getPatternsCondition().getPatterns().contains(url)) {
                    list.add(mappingInfo);
                }
            }
        }
        List<ReturnInfo> result = new ArrayList<>();
        list.forEach(mappingInfo -> {
            HandlerMethod method = requestMappingHandlerMapping.getHandlerMethods().get(mappingInfo);
            result.add(new ReturnInfo(mappingInfo, method));
        });
        return ResultEntity.success(SystemCode.QUERY_OK).setData(result)
                .setFilterNames("handlerMethod", "requestMappingInfo").build();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void afterPropertiesSet() throws Exception {
        Field field = ReflectUtil.findField(requestMappingHandlerMapping.getClass(), "mappingRegistry");
        if (field != null) {
            field.setAccessible(true);
            mappingRegistry = field.get(requestMappingHandlerMapping);
            if (mappingRegistry != null) {
                Field urlLookupField = ReflectUtil.findField(mappingRegistry.getClass(), "urlLookup");
                if (urlLookupField != null) {
                    urlLookupField.setAccessible(true);
                    MultiValueMap<String, RequestMappingInfo> urlLookup = (MultiValueMap<String, RequestMappingInfo>) urlLookupField
                            .get(mappingRegistry);
                    this.urlLookup = urlLookup;
                }
                Field mappingLookupField = ReflectUtil.findField(mappingRegistry.getClass(), "mappingLookup");
                mappingLookupField.setAccessible(true);
                this.mappingLookup = (Map<RequestMappingInfo, HandlerMethod>) mappingLookupField.get(mappingRegistry);
            }
        }
    }

    public Swagger getDocumentation(@RequestParam(value = "group", required = false) String swaggerGroup,
            HttpServletRequest servletRequest) {
        try {
            String groupName = Optional.fromNullable(swaggerGroup).or(Docket.DEFAULT_GROUP_NAME);
            Documentation documentation = documentationCache.documentationByGroup(groupName);
            if (documentation == null) {
                return null;
            }
            Swagger swagger = mapper.mapDocumentation(documentation);
            UriComponents uriComponents = (UriComponents) componentsFromMethod.invoke(null, servletRequest,
                    swagger.getBasePath());
            swagger.basePath(Strings.isNullOrEmpty(uriComponents.getPath()) ? "/" : uriComponents.getPath());
            if (Strings.isNullOrEmpty(swagger.getHost())) {
                swagger.host(hostName(uriComponents));
            }
            return swagger;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String hostName(UriComponents uriComponents) {
        if ("DEFAULT".equals(hostNameOverride)) {
            String host = uriComponents.getHost();
            int port = uriComponents.getPort();
            if (port > -1) {
                return String.format("%s:%d", host, port);
            }
            return host;
        }
        return hostNameOverride;
    }

    @Override
    public void onWebApplicationEvent(ContextRefreshedEvent event) {
        if (init) {
            return;
        }
        init = true;
        try {
            PropertySourcedRequestMappingHandlerMapping p = SpringBeanUtil
                    .getBean(PropertySourcedRequestMappingHandlerMapping.class);
            Field handlerMethodsField = ReflectUtil.findField(PropertySourcedRequestMappingHandlerMapping.class,
                    "handlerMethods");
            handlerMethodsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, HandlerMethod> handlerMethods = (Map<String, HandlerMethod>) handlerMethodsField.get(p);
            this.method = handlerMethods.get("/v2/api-docs");
            Field handlerField = ReflectUtil.findField(PropertySourcedRequestMappingHandlerMapping.class, "handler");
            handlerField.setAccessible(true);
            this.handler = handlerField.get(p);
            this.swCon = (Swagger2Controller) this.method.getBean();
            this.jsonSerializer = ReflectUtil.getFieldValue(this.swCon, "jsonSerializer", JsonSerializer.class);
            this.hostNameOverride = ReflectUtil.getFieldValue(this.swCon, "hostNameOverride", String.class);
            this.documentationCache = ReflectUtil.getFieldValue(this.swCon, "documentationCache",
                    DocumentationCache.class);
            this.mapper = ReflectUtil.getFieldValue(this.swCon, "mapper", ServiceModelToSwagger2Mapper.class);
            this.hostNameOverride = ReflectUtil.getFieldValue(this.swCon, "hostNameOverride", String.class);
            this.objectMapper = ReflectUtil.getFieldValue(jsonSerializer, "objectMapper", ObjectMapper.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Data
    public static class ReturnInfo {
        RequestMappingInfo requestMappingInfo;
        Map<String, Object> requestMappingInfoSimple = new HashMap<>();
        HandlerMethod handlerMethod;
        /**
         * parameterName:java.lang.String
         **/
        List<ParameterInfo> parameterTypes = new ArrayList<>();
        String returnType;
        String methodName;
        String version;

        public ReturnInfo(RequestMappingInfo requestMappingInfo, HandlerMethod handlerMethod) {
            super();
            this.requestMappingInfo = requestMappingInfo;
            if (!requestMappingInfo.getConsumesCondition().isEmpty()) {
                requestMappingInfoSimple.put("consumesCondition", requestMappingInfo.getConsumesCondition());
            }
            if (!requestMappingInfo.getHeadersCondition().isEmpty()) {
                requestMappingInfoSimple.put("headersCondition", requestMappingInfo.getHeadersCondition());
            }
            if (!requestMappingInfo.getMethodsCondition().isEmpty()) {
                requestMappingInfoSimple.put("methodsCondition", requestMappingInfo.getMethodsCondition());
            }
            if (!requestMappingInfo.getParamsCondition().isEmpty()) {
                requestMappingInfoSimple.put("paramsCondition", requestMappingInfo.getParamsCondition());
            }
            if (!requestMappingInfo.getPatternsCondition().isEmpty()) {
                requestMappingInfoSimple.put("patternsCondition", requestMappingInfo.getPatternsCondition());
            }
            if (!requestMappingInfo.getProducesCondition().isEmpty()) {
                requestMappingInfoSimple.put("producesCondition", requestMappingInfo.getProducesCondition());
            }
            this.handlerMethod = handlerMethod;
            this.methodName = handlerMethod.getMethod().getName();
            this.returnType = handlerMethod.getReturnType().getParameterType().getName();
            ApiVersion apiVersion = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), ApiVersion.class);
            if (apiVersion == null) {
                this.version = "1.0.0";
            } else {
                this.version = apiVersion.value();
            }
            List<MethodParameter> methodParameterList = Arrays.asList(handlerMethod.getMethodParameters());
            String[] names = parameterNameDiscoverer.getParameterNames(handlerMethod.getMethod());
            for (int i = 0; i < methodParameterList.size(); i++) {
                ParameterInfo info = new ParameterInfo();
                MethodParameter methodParameter = methodParameterList.get(i);
                String name = names[i];
                String type = methodParameter.getParameterType().getName();
                info.setIndex(i);
                info.setName(name);
                info.setRemark(null);
                ApiParam apiParam = methodParameter.getParameterAnnotation(ApiParam.class);
                if (apiParam == null) {
                    info.setRequire(false);
                    info.setRemark("无");
                } else {
                    info.setRequire(apiParam.required());
                    info.setRemark(apiParam.value());
                }
                info.setJavaType(type);
                parameterTypes.add(info);
            }
        }

        public ReturnInfo() {
            super();
        }
    }

    @Data
    public static class ParameterInfo {
        private String name;
        private Integer index;
        private String javaType;
        private String remark;
        private boolean require;
    }

}
