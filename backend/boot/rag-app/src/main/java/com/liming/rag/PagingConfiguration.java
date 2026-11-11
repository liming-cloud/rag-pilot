package com.liming.rag;

import com.liming.rag.shared.Paged;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 分页参数（D-039）：控制器方法写一个 {@link Paged.Request} 参数，取查询参数 page、size；不传按 0，由
 * {@link Paged.Request} 统一归一（第 1 页、20 条，最多 100 条）。不是整数时返回 400。
 */
@Configuration
class PagingConfiguration implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageRequestResolver());
    }

    private static final class PageRequestResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == Paged.Request.class;
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer container,
                NativeWebRequest request, @Nullable WebDataBinderFactory binderFactory) throws Exception {
            return new Paged.Request(intParam(parameter, request, binderFactory, "page"),
                    intParam(parameter, request, binderFactory, "size"));
        }

        private static int intParam(MethodParameter parameter, NativeWebRequest request,
                @Nullable WebDataBinderFactory binderFactory, String name) throws Exception {
            String value = request.getParameter(name);
            if (value == null || value.isBlank() || binderFactory == null) {
                return 0;
            }
            Integer parsed = binderFactory.createBinder(request, null, name)
                    .convertIfNecessary(value.strip(), Integer.class, parameter);
            return parsed == null ? 0 : parsed;
        }
    }
}
