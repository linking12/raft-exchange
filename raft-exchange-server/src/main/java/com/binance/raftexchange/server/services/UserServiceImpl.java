package com.binance.raftexchange.server.services;

import com.binance.raftexchange.server.stubs.usuario.Usuario;
import com.binance.raftexchange.server.stubs.usuario.UsuarioServiceGrpc.UsuarioServiceImplBase;

import io.grpc.stub.StreamObserver;

public class UserServiceImpl extends UsuarioServiceImplBase {
    @Override
    public void salve(Usuario request, StreamObserver<Usuario> responseObserver) {
        Usuario usuarioSaved = Usuario.newBuilder().setEmail("teste_email@saved.com").setFullname(request.getFullname())
                .setPassword("13131313").build();
        responseObserver.onNext(usuarioSaved);
        responseObserver.onCompleted();
    }
}
