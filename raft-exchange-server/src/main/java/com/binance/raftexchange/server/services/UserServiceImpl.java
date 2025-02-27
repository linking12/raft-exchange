package com.binance.raftexchange.server.services;

import com.binance.raftexchange.stubs.usuario.Usuario;
import com.binance.raftexchange.stubs.usuario.UsuarioServiceGrpc;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class UserServiceImpl extends UsuarioServiceGrpc.UsuarioServiceImplBase {
    @Override
    public void salve(Usuario request, StreamObserver<Usuario> responseObserver) {
        Usuario usuarioSaved = Usuario.newBuilder().setEmail("teste_email@saved.com").setFullname(request.getFullname())
                .setPassword("13131313").build();
        responseObserver.onNext(usuarioSaved);
        responseObserver.onCompleted();
    }


}
