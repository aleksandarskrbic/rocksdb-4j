package com.github.aleksandarskrbic.rocks4j.repository;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import com.github.aleksandarskrbic.rocks4j.configuration.RocksDBConfiguration;
import com.github.aleksandarskrbic.rocks4j.configuration.RocksDBConnection;
import com.github.aleksandarskrbic.rocks4j.exception.DeserializationException;
import com.github.aleksandarskrbic.rocks4j.exception.SerDeException;
import com.github.aleksandarskrbic.rocks4j.exception.SerializationException;
import com.github.aleksandarskrbic.rocks4j.kv.AsyncKeyValueRepository;
import com.github.aleksandarskrbic.rocks4j.mapper.Mapper;
import com.github.aleksandarskrbic.rocks4j.mapper.RocksDBMapperFactory;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class that should be extended by the concrete asynchronous repository.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 */
public class AsyncRocksDBKeyValueRepository<K, V> extends RocksDBConnection implements AsyncKeyValueRepository<K, V> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncRocksDBKeyValueRepository.class);

    private final Mapper<K> keyMapper;
    private final Mapper<V> valueMapper;
    private final ExecutorService executorService = Executors.newFixedThreadPool(5); // TODO: Externalize number of thread
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Default constructor which automatically infers key and value types needed for mapper creation.
     * Uses {@link com.github.aleksandarskrbic.rocks4j.mapper.RocksDBMapper}.
     *
     * @param configuration for {@link RocksDBConnection}.
     */
    public AsyncRocksDBKeyValueRepository(final RocksDBConfiguration configuration) {
        super(configuration);
        this.keyMapper = RocksDBMapperFactory.mapperFor(extractKeyType());
        this.valueMapper = RocksDBMapperFactory.mapperFor(extractValueType());
    }

    /**
     *
     * @param configuration for {@link RocksDBConnection}.
     * @param keyType for mapper.
     * @param valueType for mapper.
     */
    public AsyncRocksDBKeyValueRepository(
            final RocksDBConfiguration configuration,
            final Class<K> keyType,
            final Class<V> valueType
    ) {
        super(configuration);
        this.keyMapper = RocksDBMapperFactory.mapperFor(keyType);
        this.valueMapper = RocksDBMapperFactory.mapperFor(valueType);
    }

    /**
     *
     * @param configuration for {@link RocksDBConnection}.
     * @param keyMapper custom key mapper that implements {@link Mapper}.
     * @param valueMapper custom value mapper that implements {@link Mapper}.
     */
    public AsyncRocksDBKeyValueRepository(
            final RocksDBConfiguration configuration,
            final Mapper<K> keyMapper,
            final Mapper<V> valueMapper
    ) {
        super(configuration);
        this.keyMapper = keyMapper;
        this.valueMapper = valueMapper;
    }

    @Override
    public CompletableFuture<Void> save(
            final K key,
            final V value
    ) {
        return CompletableFuture.runAsync(() -> {
            lock.writeLock().lock();
            try {
                final byte[] serializedKey = keyMapper.serialize(key);
                final byte[] serializedValue = valueMapper.serialize(value);
                rocksDB.put(serializedKey, serializedValue);
            } catch (final SerializationException exception) {
                LOGGER.error("Serialization exception occurred during save operation. {}", exception.getMessage());
            } catch (final RocksDBException exception) {
                LOGGER.error("RocksDBException occurred during save operation. {}", exception.getMessage());
            }
            lock.writeLock().unlock();
        }, executorService);
    }

    @Override
    public CompletableFuture<Optional<V>> findByKey(final K key) {
        return CompletableFuture.supplyAsync(() -> {
            lock.readLock().lock();
            try {
                final byte[] serializedKey = keyMapper.serialize(key);
                final byte[] bytes = rocksDB.get(serializedKey);
                return Optional.ofNullable(valueMapper.deserialize(bytes));
            } catch (final SerializationException exception) {
                LOGGER.error("Serialization exception occurred during findByKey operation. {}", exception.getMessage());
            } catch (final RocksDBException exception) {
                LOGGER.error("RocksDBException occurred during findByKey operation. {}", exception.getMessage());
            } catch (final DeserializationException exception) {
                LOGGER.error("Deserialization exception occurred during findByKey operation. {}", exception.getMessage());
            } finally {
                lock.readLock().unlock();
            }
            return Optional.empty();
        }, executorService);
    }

    @Override
    public CompletableFuture<Collection<V>> findAll() {
        return CompletableFuture.supplyAsync(() -> {
            final Collection<V> result = new LinkedList<>();
            final RocksIterator iterator = rocksDB.newIterator();
            lock.readLock().lock();

            iterator.seekToFirst();
            while (iterator.isValid()) {
                try {
                    final V value = valueMapper.deserialize(iterator.value());
                    result.add(value);
                    iterator.next();
                } catch (final DeserializationException exception) {
                    LOGGER.error("Deserialization exception occurred during findAll operation. {}", exception.getMessage());
                    iterator.close();
                    return Collections.emptyList();
                }
            }
            iterator.close();
            lock.readLock().unlock();
            return result;
        }, executorService);
    }

    @Override
    public CompletableFuture<Void> deleteByKey(final K key) {
        return CompletableFuture.runAsync(() -> {
            lock.writeLock().lock();
            try {
                final byte[] serializedKey = keyMapper.serialize(key);
                rocksDB.delete(serializedKey);
            } catch (final SerializationException exception) {
                LOGGER.error("Serialization exception occurred during findByKey operation. {}", exception.getMessage());
            } catch (final RocksDBException exception) {
                LOGGER.error("RocksDBException occurred during deleteByKey operation. {}", exception.getMessage());
            } finally {
                lock.writeLock().unlock();
            }
        }, executorService);
    }

    @Override
    public CompletableFuture<Void> deleteAll() {
        return CompletableFuture.runAsync(() -> {
            final RocksIterator iterator = rocksDB.newIterator();
            lock.writeLock().lock();

            iterator.seekToFirst();
            final byte[] firstKey = getKey(iterator);

            iterator.seekToLast();
            final byte[] lastKey = getKey(iterator);

            if (firstKey == null || lastKey == null) {
                return;
            }

            try {
                rocksDB.deleteRange(firstKey, lastKey);
                rocksDB.delete(lastKey);
            } catch (final RocksDBException exception) {
                LOGGER.error("RocksDBException occurred during deleteAll operation. {}", exception.getMessage());
            } finally {
                lock.writeLock().unlock();
            }
        }, executorService);
    }

    private byte[] getKey(final RocksIterator iterator) {
        if (!iterator.isValid()) {
            return null;
        }
        return iterator.key();
    }

    @SuppressWarnings("unchecked")
    private Class<K> extractKeyType() {
        return (Class<K>) extractClass(((ParameterizedType) getGenericSuperClass()).getActualTypeArguments()[0]);
    }

    @SuppressWarnings("unchecked")
    private Class<V> extractValueType() {
        return (Class<V>) extractClass(((ParameterizedType) getGenericSuperClass()).getActualTypeArguments()[1]);
    }

    private Type getGenericSuperClass() {
        final Type superClass = getClass().getGenericSuperclass();

        if (superClass instanceof Class<?>) {
            throw new IllegalArgumentException("Internal error: TypeReference constructed without actual type information");
        }

        return superClass;
    }

    private Class<?> extractClass(final Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }

        throw new IllegalArgumentException("Internal error: TypeReference constructed without actual type information");
    }
}