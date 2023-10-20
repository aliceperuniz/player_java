import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.JavaLayerException;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;



public class Player {

    /**
     * The MPEG audio bitstream.
     */
    private Bitstream bitstream;
    /**
     * The MPEG audio decoder.
     */
    private Decoder decoder;
    /**
     * The AudioDevice where audio samples are written to.
     */
    private AudioDevice device;

    private PlayerWindow window;

    // variaveis para tocar a musica
    private int currentFrame = 0;
    private int playPause = 1;
    private int ativo = 0;
    private int index;
    private boolean proxMusica = false; // variável para controlar que será exibida a próxima música
    private boolean musicaAnterior = false; // variável para controlar que será exibida a música anterior
    private boolean emExecucao = false;
    private int tempopAtual; // marcador de tempo da música no display
    private Song musicaAtual;
    private Thread playThread;
    private Thread updateFrame;

    private final Lock lock = new ReentrantLock();

    // cria matriz bidimensional de strings (musicas):
    private String[][] musicas = new String[0][];

    //array-list que contém objetos do tipo 'Song' (playlist)
    private ArrayList<Song> playlist = new ArrayList<Song>();


    private final ActionListener buttonListenerPlayNow = e -> {
        if (playPause == 0) {
            currentFrame = 0;
        }
        // inicia a música
        playMusic();
    };

    private final ActionListener buttonListenerRemove = e -> {
        // obtém o índice da música a ser removida
        int idx = window.getSelectedSongIndex();
        // verifica se o índice da música a ser removida é o mesmo que o índice da música atualmente em execução
        if (idx == index && emExecucao) {
            // fecha objetos relacionados à reprodução e atualiza a interface.
            stopMusic(playThread, bitstream, device, window, emExecucao);
            // se a música atual está em execução e há músicas restantes na playlist após a música atual, uma nova thread de reprodução é criada chamando a função playNow e é iniciada
            if (index < playlist.size()-1) {
                playThread = new Thread(this::playMusic);
                playThread.start();
            }
        }
        // diminui o index da música tocando se uma música antes dela for removida
        if (idx < index) {
            index -= 1;
        }
        // Garante que o índice da música atual em execução não fique negativo, caso o índice da música a ser removida seja menor que o índice da música atual
        if (index < 0) {
            index = 0;
        }
        // remove a música da playlist
        playlist.remove(idx);
        // remove a música da lista de músicas
        musicas = removeMusic(musicas, idx);
        // atualiza a lista de reprodução
        this.window.setQueueList(musicas);
    };

    private final ActionListener buttonListenerAddSong = e -> {
        lock.lock();
        try {
            // escolhe um arquivo mp3
            Song music = this.window.openFileChooser();
            // adciona o arquivo/música
            playlist.add(music);
            // coleta informações da música
            String[] musicInfo = music.getDisplayInfo();
            int size = musicas.length;
            // atualiza o tamanho da lista
            musicas = Arrays.copyOf(musicas, size + 1);
            // adiciona nova música
            musicas[size] = musicInfo;
            // atualiza a fila
            this.window.setQueueList(musicas);
        } finally {
            lock.unlock();
        }

    };
    private final ActionListener buttonListenerPlayPause = e -> {
        if (playPause == 1) {
            playPause = 0;
        }    //caso o botão esteja habilitado como pause, muda para play
        else {
            playPause = 1;
            //caso esteja como play, muda para pause
        }

        window.setPlayPauseButtonIcon(playPause); // seta a mudança

    };
    private final ActionListener buttonListenerStop = e -> {
        // caso alguma música esteja em reprodução
        if (emExecucao) {
            stopMusic(playThread, bitstream, device, window, emExecucao);
        }
    };
    private final ActionListener buttonListenerNext = e -> {
        // interrompe a execução atual
        threadInterrupt(playThread, bitstream, device);
        // será exibida a próxima música
        proxMusica = true;
        // se estiver pausada, a próxima inicia despausada
        if (playPause == 0) {
            playPause = 1;
        }
        // inicia a próxima música
        playThread = new Thread(this::playMusic);
        playThread.start();
    };

    private void threadInterrupt(Thread playThread, Bitstream bitstream, AudioDevice device) {
        if (bitstream != null) {   // verifica se o objeto Bitstream não é nulo
            playThread.interrupt();
            boolean naoInterrompido = true;

            while(naoInterrompido) {
                if (playThread.isInterrupted()) {  //verifica se a thread de reprodução (playThread) foi interrompida
                    try {
                        bitstream.close();  // fecha os objetos Bitstream e AudioDevice
                        device.close();
                    } catch (BitstreamException exception) {
                        throw new RuntimeException(exception);
                    }
                    naoInterrompido = false;
                }
            }
        }
    }

    private final ActionListener buttonListenerPrevious = e -> {
        // interrompe a música atual
        threadInterrupt(playThread, bitstream, device);
        // o nome da música anterior é mostrado no display
        musicaAnterior = true;
        // música começa no play
        if (playPause == 0) {
            playPause = 1;
        }
        // toca a música anterior
        playThread = new Thread(this::playMusic);
        playThread.start();
    };
    private final ActionListener buttonListenerShuffle = e -> {};
    private final ActionListener buttonListenerLoop = e -> {};
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        private int previousState;
        @Override
        public void mouseReleased(MouseEvent e) {
            updateFrame = new Thread(() -> {
                // atualiza a barra de tempo na interface definindo o tempo atual de reprodução
                window.setTime((int) (tempopAtual * (int) musicaAtual.getMsPerFrame()), (int) musicaAtual.getMsLength());

                // verifica se o tempo atual de reprodução é menor que o quadro atual. Isso pode indicar que a música foi rebobinada.
                if (tempopAtual < currentFrame) {
                    // para a música atual e reinicia os objetos
                    stopMusic(playThread, bitstream, device, window, emExecucao);
                    initializeObjects();

                    // define as informações da música atual
                    window.setPlayingSongInfo(musicaAtual.getTitle(), musicaAtual.getAlbum(), musicaAtual.getArtist());

                    //zera o quadro atual, para permitir que a música seja retomada do início
                    currentFrame = 0;
                }

                // responsável por pular para um determinado quadro na música
                try {
                    skipToFrame(tempopAtual);
                } catch (BitstreamException exception) {
                    throw new RuntimeException(exception);
                }

                window.setTime((currentFrame * (int) musicaAtual.getMsPerFrame()), (int) musicaAtual.getMsLength());  // atualiza novamente a barra de tempo na interface
                window.setPlayPauseButtonIcon(playPause); // define o ícone do botão de reprodução/pausa na interface
                window.setEnabledPlayPauseButton(true);   // "setEnable..."  ->  habilita ou desabilita vários botões e elementos da interface com base no estado da reprodução
                window.setEnabledStopButton(true);
                window.setEnabledPreviousButton(index != 0);
                window.setEnabledNextButton(index != playlist.size()-1);
                window.setEnabledScrubber(true);
                playPause = previousState;  // restaura o estado anterior
            });

            updateFrame.start();
        }

        @Override
        public void mousePressed(MouseEvent e) {
            // manipulador de eventos para o evento de pressionar o mouse
            previousState = playPause;
            playPause = 1;
            tempopAtual = (int) (window.getScrubberValue() / musicaAtual.getMsPerFrame());
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            // manipulador de eventos para o evento de arrastar o mouse
            tempopAtual = (int) (window.getScrubberValue() / musicaAtual.getMsPerFrame());
        }
    };


    private void playMusic() {
        // interrompe música, caso ela ainda esteja em execução na thread:
        threadInterrupt(playThread, bitstream, device);

        playThread = new Thread(() -> {
            // coloca a música para começar no "play"
            currentFrame = 0;
            playPause = 1;
            emExecucao = true;
            // selecionando musica
            if (proxMusica) {
                index += 1; // próxima música
            } else if (musicaAnterior) {
                index -= 1; // música anterior
            } else {
                index = window.getSelectedSongIndex(); // música selecionada
            }

            if (index < 0) {
                index = 0;  // Não deixa que o index fique negativo
            }
            musicaAtual = playlist.get(index);
            // apaga as variáveis de próxima música e da anterior
            proxMusica = false;
            musicaAnterior = false;

            // inicializa objetos
            initializeObjects();

            // exibe informações da música em execução
            window.setPlayingSongInfo(musicaAtual.getTitle(), musicaAtual.getAlbum(), musicaAtual.getArtist());

            // toca a musica
            while (playPause == 1) {
                //lock.lock();
                try {
                    // atualiza o tempo da música e habilita vários botões na interface do mini player
                    window.setTime((currentFrame * (int) musicaAtual.getMsPerFrame()), (int) musicaAtual.getMsLength());
                    window.setPlayPauseButtonIcon(playPause);
                    window.setEnabledPlayPauseButton(true);
                    window.setEnabledStopButton(true);
                    window.setEnabledPreviousButton(index != 0);
                    window.setEnabledNextButton(index != playlist.size() - 1);
                    window.setEnabledScrubber(true);

                    // resetar o miniplayer e fechar os objetos quando a musica acabar
                    if (!playNextFrame()) {
                        // verifica se a música atual é a última na playlist
                        if (index == playlist.size() - 1) {
                            stopMusic(playThread, bitstream, device, window, emExecucao);
                        }
                        // uma nova thread de reprodução é criada chamando a função playNow, e essa nova thread é iniciada
                        else {
                            playThread.interrupt();
                            proxMusica = true;
                            playThread = new Thread(this::playMusic);
                            playThread.start();
                        }
                    }
                } catch (JavaLayerException exception) {
                    throw new RuntimeException(exception);
                }
                //lock.unlock();
            }
        });

        playThread.start();
    }

    private String[][] removeMusic(String[][] musics, int idx) {
        // matriz de músicas com um tamanho a menos
        String[][] newList = new String[musics.length - 1][];
        // passa os elementos para a nova matriz (exceto o elemento do index que foi removido)
        System.arraycopy(musics, 0, newList, 0, idx);
        System.arraycopy(musics, idx + 1, newList, idx, musics.length - idx - 1);
        // retorna a lista sem o elemento de index removido
        return newList;
    }

    private void stopMusic(Thread playThread, Bitstream bitstream, AudioDevice device, PlayerWindow window, boolean isPlaying) {
        // interrompe a thread
        playThread.interrupt();

        // fecha o bitstream e o device
        try {
            bitstream.close();
            device.close();
        } catch (BitstreamException exception) {
            throw new RuntimeException(exception);
        }

        // reinicia os botões e a tela das músicas
        window.setPlayPauseButtonIcon(0);
        window.setEnabledPlayPauseButton(false);
        window.setEnabledStopButton(false);
        window.setEnabledPreviousButton(false);
        window.setEnabledNextButton(false);
        window.setEnabledScrubber(false);
        isPlaying = false;
        window.resetMiniPlayer();
    }


    private void initializeObjects() {
        // inicializa objetos
        try {
            device = FactoryRegistry.systemRegistry().createAudioDevice();
            device.open(decoder = new Decoder());
            bitstream = new Bitstream(musicaAtual.getBufferedInputStream());
        } catch (JavaLayerException | FileNotFoundException exception) {
            throw new RuntimeException(exception);
        }
    }

    public Player() {
        //
        EventQueue.invokeLater(() -> window = new PlayerWindow(
                "A&A_Player",
                //queue:
                musicas,
                buttonListenerPlayNow,
                buttonListenerRemove,
                buttonListenerAddSong,
                buttonListenerShuffle,
                buttonListenerPrevious,
                buttonListenerPlayPause,
                buttonListenerStop,
                buttonListenerNext,
                buttonListenerLoop,
                scrubberMouseInputAdapter)
        );
    }

    //<editor-fold desc="Essential">
    /**
     * @return False if there are no more frames to play.
     */
    private boolean playNextFrame() throws JavaLayerException {
        if (device != null) {
            Header h = bitstream.readFrame();
            if (h == null) return false;
            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            device.write(output.getBuffer(), 0, output.getBufferLength());
            bitstream.closeFrame();
            currentFrame++;
        }
        return true;
    }

    /**
     * @return False if there are no more frames to skip.
     */
    private boolean skipNextFrame() throws BitstreamException {
        Header h = bitstream.readFrame();
        if (h == null) return false;
        bitstream.closeFrame();
        currentFrame++;
        return true;
    }

    /**
     * Skips bitstream to the target frame if the new frame is higher than the current one.
     *
     * @param newFrame Frame to skip to.
     */
    private void skipToFrame(int newFrame) throws BitstreamException {
        if (newFrame > currentFrame) {
            int framesToSkip = newFrame - currentFrame;
            boolean condition = true;
            while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
        }
    }
    //</editor-fold>
}