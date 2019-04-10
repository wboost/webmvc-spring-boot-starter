package top.wboost.common.spring.boot.webmvc.annotation;

import java.lang.annotation.*;

/**
 * 方法版本号
 * @className ApiVersion
 * @author jwSun
 * @date 2018年5月28日 下午2:40:57
 * @version 1.0.0
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiVersion {

    String value() default GlobalForApiConfig.DEFAULT_VERSION_SHOW;

}