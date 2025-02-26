package com.binance.raftexchange.client.stubs.usuario;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.41.0)",
    comments = "Source: Usuario.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class UsuarioServiceGrpc {

  private UsuarioServiceGrpc() {}

  public static final String SERVICE_NAME = "UsuarioService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuario,
      com.binance.raftexchange.client.stubs.usuario.Usuario> getSalveMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "salve",
      requestType = com.binance.raftexchange.client.stubs.usuario.Usuario.class,
      responseType = com.binance.raftexchange.client.stubs.usuario.Usuario.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuario,
      com.binance.raftexchange.client.stubs.usuario.Usuario> getSalveMethod() {
    io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuario, com.binance.raftexchange.client.stubs.usuario.Usuario> getSalveMethod;
    if ((getSalveMethod = UsuarioServiceGrpc.getSalveMethod) == null) {
      synchronized (UsuarioServiceGrpc.class) {
        if ((getSalveMethod = UsuarioServiceGrpc.getSalveMethod) == null) {
          UsuarioServiceGrpc.getSalveMethod = getSalveMethod =
              io.grpc.MethodDescriptor.<com.binance.raftexchange.client.stubs.usuario.Usuario, com.binance.raftexchange.client.stubs.usuario.Usuario>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "salve"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.binance.raftexchange.client.stubs.usuario.Usuario.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.binance.raftexchange.client.stubs.usuario.Usuario.getDefaultInstance()))
              .setSchemaDescriptor(new UsuarioServiceMethodDescriptorSupplier("salve"))
              .build();
        }
      }
    }
    return getSalveMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuario,
      com.binance.raftexchange.client.stubs.usuario.Usuarios> getSalveAllStreamClientMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "salveAllStreamClient",
      requestType = com.binance.raftexchange.client.stubs.usuario.Usuario.class,
      responseType = com.binance.raftexchange.client.stubs.usuario.Usuarios.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuario,
      com.binance.raftexchange.client.stubs.usuario.Usuarios> getSalveAllStreamClientMethod() {
    io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuario, com.binance.raftexchange.client.stubs.usuario.Usuarios> getSalveAllStreamClientMethod;
    if ((getSalveAllStreamClientMethod = UsuarioServiceGrpc.getSalveAllStreamClientMethod) == null) {
      synchronized (UsuarioServiceGrpc.class) {
        if ((getSalveAllStreamClientMethod = UsuarioServiceGrpc.getSalveAllStreamClientMethod) == null) {
          UsuarioServiceGrpc.getSalveAllStreamClientMethod = getSalveAllStreamClientMethod =
              io.grpc.MethodDescriptor.<com.binance.raftexchange.client.stubs.usuario.Usuario, com.binance.raftexchange.client.stubs.usuario.Usuarios>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "salveAllStreamClient"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.binance.raftexchange.client.stubs.usuario.Usuario.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.binance.raftexchange.client.stubs.usuario.Usuarios.getDefaultInstance()))
              .setSchemaDescriptor(new UsuarioServiceMethodDescriptorSupplier("salveAllStreamClient"))
              .build();
        }
      }
    }
    return getSalveAllStreamClientMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuarios,
      com.binance.raftexchange.client.stubs.usuario.Usuario> getSalveAllStreamServerMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "salveAllStreamServer",
      requestType = com.binance.raftexchange.client.stubs.usuario.Usuarios.class,
      responseType = com.binance.raftexchange.client.stubs.usuario.Usuario.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuarios,
      com.binance.raftexchange.client.stubs.usuario.Usuario> getSalveAllStreamServerMethod() {
    io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuarios, com.binance.raftexchange.client.stubs.usuario.Usuario> getSalveAllStreamServerMethod;
    if ((getSalveAllStreamServerMethod = UsuarioServiceGrpc.getSalveAllStreamServerMethod) == null) {
      synchronized (UsuarioServiceGrpc.class) {
        if ((getSalveAllStreamServerMethod = UsuarioServiceGrpc.getSalveAllStreamServerMethod) == null) {
          UsuarioServiceGrpc.getSalveAllStreamServerMethod = getSalveAllStreamServerMethod =
              io.grpc.MethodDescriptor.<com.binance.raftexchange.client.stubs.usuario.Usuarios, com.binance.raftexchange.client.stubs.usuario.Usuario>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "salveAllStreamServer"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.binance.raftexchange.client.stubs.usuario.Usuarios.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.binance.raftexchange.client.stubs.usuario.Usuario.getDefaultInstance()))
              .setSchemaDescriptor(new UsuarioServiceMethodDescriptorSupplier("salveAllStreamServer"))
              .build();
        }
      }
    }
    return getSalveAllStreamServerMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuario,
      com.binance.raftexchange.client.stubs.usuario.Usuario> getSalveAllStreamMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "salveAllStream",
      requestType = com.binance.raftexchange.client.stubs.usuario.Usuario.class,
      responseType = com.binance.raftexchange.client.stubs.usuario.Usuario.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuario,
      com.binance.raftexchange.client.stubs.usuario.Usuario> getSalveAllStreamMethod() {
    io.grpc.MethodDescriptor<com.binance.raftexchange.client.stubs.usuario.Usuario, com.binance.raftexchange.client.stubs.usuario.Usuario> getSalveAllStreamMethod;
    if ((getSalveAllStreamMethod = UsuarioServiceGrpc.getSalveAllStreamMethod) == null) {
      synchronized (UsuarioServiceGrpc.class) {
        if ((getSalveAllStreamMethod = UsuarioServiceGrpc.getSalveAllStreamMethod) == null) {
          UsuarioServiceGrpc.getSalveAllStreamMethod = getSalveAllStreamMethod =
              io.grpc.MethodDescriptor.<com.binance.raftexchange.client.stubs.usuario.Usuario, com.binance.raftexchange.client.stubs.usuario.Usuario>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "salveAllStream"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.binance.raftexchange.client.stubs.usuario.Usuario.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.binance.raftexchange.client.stubs.usuario.Usuario.getDefaultInstance()))
              .setSchemaDescriptor(new UsuarioServiceMethodDescriptorSupplier("salveAllStream"))
              .build();
        }
      }
    }
    return getSalveAllStreamMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static UsuarioServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<UsuarioServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<UsuarioServiceStub>() {
        @java.lang.Override
        public UsuarioServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new UsuarioServiceStub(channel, callOptions);
        }
      };
    return UsuarioServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static UsuarioServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<UsuarioServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<UsuarioServiceBlockingStub>() {
        @java.lang.Override
        public UsuarioServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new UsuarioServiceBlockingStub(channel, callOptions);
        }
      };
    return UsuarioServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static UsuarioServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<UsuarioServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<UsuarioServiceFutureStub>() {
        @java.lang.Override
        public UsuarioServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new UsuarioServiceFutureStub(channel, callOptions);
        }
      };
    return UsuarioServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public static abstract class UsuarioServiceImplBase implements io.grpc.BindableService {

    /**
     * <pre>
     * Unary
     * </pre>
     */
    public void salve(com.binance.raftexchange.client.stubs.usuario.Usuario request,
        io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSalveMethod(), responseObserver);
    }

    /**
     * <pre>
     * Client Streaming
     * </pre>
     */
    public io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario> salveAllStreamClient(
        io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuarios> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getSalveAllStreamClientMethod(), responseObserver);
    }

    /**
     * <pre>
     * Server Streaming
     * </pre>
     */
    public void salveAllStreamServer(com.binance.raftexchange.client.stubs.usuario.Usuarios request,
        io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSalveAllStreamServerMethod(), responseObserver);
    }

    /**
     * <pre>
     * BI-DIRECIONAL STREAMING
     * </pre>
     */
    public io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario> salveAllStream(
        io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getSalveAllStreamMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getSalveMethod(),
            io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                com.binance.raftexchange.client.stubs.usuario.Usuario,
                com.binance.raftexchange.client.stubs.usuario.Usuario>(
                  this, METHODID_SALVE)))
          .addMethod(
            getSalveAllStreamClientMethod(),
            io.grpc.stub.ServerCalls.asyncClientStreamingCall(
              new MethodHandlers<
                com.binance.raftexchange.client.stubs.usuario.Usuario,
                com.binance.raftexchange.client.stubs.usuario.Usuarios>(
                  this, METHODID_SALVE_ALL_STREAM_CLIENT)))
          .addMethod(
            getSalveAllStreamServerMethod(),
            io.grpc.stub.ServerCalls.asyncServerStreamingCall(
              new MethodHandlers<
                com.binance.raftexchange.client.stubs.usuario.Usuarios,
                com.binance.raftexchange.client.stubs.usuario.Usuario>(
                  this, METHODID_SALVE_ALL_STREAM_SERVER)))
          .addMethod(
            getSalveAllStreamMethod(),
            io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
              new MethodHandlers<
                com.binance.raftexchange.client.stubs.usuario.Usuario,
                com.binance.raftexchange.client.stubs.usuario.Usuario>(
                  this, METHODID_SALVE_ALL_STREAM)))
          .build();
    }
  }

  /**
   */
  public static final class UsuarioServiceStub extends io.grpc.stub.AbstractAsyncStub<UsuarioServiceStub> {
    private UsuarioServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected UsuarioServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new UsuarioServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Unary
     * </pre>
     */
    public void salve(com.binance.raftexchange.client.stubs.usuario.Usuario request,
        io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSalveMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Client Streaming
     * </pre>
     */
    public io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario> salveAllStreamClient(
        io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuarios> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getSalveAllStreamClientMethod(), getCallOptions()), responseObserver);
    }

    /**
     * <pre>
     * Server Streaming
     * </pre>
     */
    public void salveAllStreamServer(com.binance.raftexchange.client.stubs.usuario.Usuarios request,
        io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getSalveAllStreamServerMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * BI-DIRECIONAL STREAMING
     * </pre>
     */
    public io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario> salveAllStream(
        io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getSalveAllStreamMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   */
  public static final class UsuarioServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<UsuarioServiceBlockingStub> {
    private UsuarioServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected UsuarioServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new UsuarioServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Unary
     * </pre>
     */
    public com.binance.raftexchange.client.stubs.usuario.Usuario salve(com.binance.raftexchange.client.stubs.usuario.Usuario request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSalveMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Server Streaming
     * </pre>
     */
    public java.util.Iterator<com.binance.raftexchange.client.stubs.usuario.Usuario> salveAllStreamServer(
        com.binance.raftexchange.client.stubs.usuario.Usuarios request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getSalveAllStreamServerMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class UsuarioServiceFutureStub extends io.grpc.stub.AbstractFutureStub<UsuarioServiceFutureStub> {
    private UsuarioServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected UsuarioServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new UsuarioServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Unary
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.binance.raftexchange.client.stubs.usuario.Usuario> salve(
        com.binance.raftexchange.client.stubs.usuario.Usuario request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSalveMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SALVE = 0;
  private static final int METHODID_SALVE_ALL_STREAM_SERVER = 1;
  private static final int METHODID_SALVE_ALL_STREAM_CLIENT = 2;
  private static final int METHODID_SALVE_ALL_STREAM = 3;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final UsuarioServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(UsuarioServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_SALVE:
          serviceImpl.salve((com.binance.raftexchange.client.stubs.usuario.Usuario) request,
              (io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario>) responseObserver);
          break;
        case METHODID_SALVE_ALL_STREAM_SERVER:
          serviceImpl.salveAllStreamServer((com.binance.raftexchange.client.stubs.usuario.Usuarios) request,
              (io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_SALVE_ALL_STREAM_CLIENT:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.salveAllStreamClient(
              (io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuarios>) responseObserver);
        case METHODID_SALVE_ALL_STREAM:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.salveAllStream(
              (io.grpc.stub.StreamObserver<com.binance.raftexchange.client.stubs.usuario.Usuario>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class UsuarioServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    UsuarioServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.binance.raftexchange.client.stubs.usuario.UsuarioProtos.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("UsuarioService");
    }
  }

  private static final class UsuarioServiceFileDescriptorSupplier
      extends UsuarioServiceBaseDescriptorSupplier {
    UsuarioServiceFileDescriptorSupplier() {}
  }

  private static final class UsuarioServiceMethodDescriptorSupplier
      extends UsuarioServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    UsuarioServiceMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (UsuarioServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new UsuarioServiceFileDescriptorSupplier())
              .addMethod(getSalveMethod())
              .addMethod(getSalveAllStreamClientMethod())
              .addMethod(getSalveAllStreamServerMethod())
              .addMethod(getSalveAllStreamMethod())
              .build();
        }
      }
    }
    return result;
  }
}
