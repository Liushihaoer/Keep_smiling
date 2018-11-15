package com.whislte;

public class ApiResponse {

    private String message;
    private Boolean success;
    private Object result;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    @Override
    public String toString() {
        return "ApiResponse [message=" + message + ", success=" + success + ", result=" + result + "]";
    }

}