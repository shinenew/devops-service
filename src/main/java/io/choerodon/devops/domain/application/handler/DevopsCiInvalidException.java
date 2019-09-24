package io.choerodon.devops.domain.application.handler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author zmf
 */
public class DevopsCiInvalidException extends RuntimeException {
    private final transient Object[] parameters;

    private String code;
    private String traceMessage;

    /**
     * 构造器
     *
     * @param code       异常code
     * @param parameters parameters
     */
    public DevopsCiInvalidException(String code, Object... parameters) {
        super(code);
        this.parameters = parameters;
        this.code = code;
    }

    public DevopsCiInvalidException(String code, Throwable cause, Object... parameters) {
        super(code, cause);
        this.parameters = parameters;
        this.code = code;
    }

    public DevopsCiInvalidException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.parameters = new Object[]{};
    }


    public DevopsCiInvalidException(Throwable cause, Object... parameters) {
        super(cause);
        this.parameters = parameters;
    }

    public Object[] getParameters() {
        return parameters;
    }

    public String getCode() {
        return code;
    }

    public String getTrace() {
        if (traceMessage == null) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(outputStream);
            this.printStackTrace(ps);
            ps.flush();
            this.traceMessage = new String(outputStream.toByteArray());
        }
        return traceMessage;
    }

    public Map<String, Object> toMap() {
        HashMap<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", super.getMessage());
        return map;
    }
}
